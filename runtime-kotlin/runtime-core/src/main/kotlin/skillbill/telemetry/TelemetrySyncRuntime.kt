package skillbill.telemetry

import skillbill.ports.persistence.TelemetryOutboxRecord
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryClient
import java.io.IOException
import java.nio.file.Path

object TelemetrySyncRuntime {
  fun disabledSync(settings: TelemetrySettings): SyncResult = disabledSyncResult(settings)

  fun syncTelemetry(
    settings: TelemetrySettings,
    outboxRepository: TelemetryOutboxRepository,
    client: TelemetryClient,
  ): SyncResult = if (!settings.enabled) {
    disabledSyncResult(settings)
  } else {
    syncEnabledTelemetry(settings, outboxRepository, client)
  }

  fun syncResultPayload(result: SyncResult): Map<String, Any?> = buildMap {
    put("config_path", result.configPath.toString())
    put("telemetry_enabled", result.telemetryEnabled)
    put("telemetry_level", result.telemetryLevel)
    put("sync_target", telemetrySyncTarget(result))
    put("remote_configured", result.remoteConfigured)
    put("proxy_configured", result.proxyConfigured)
    put("proxy_url", result.proxyUrl)
    put("custom_proxy_url", result.customProxyUrl)
    put("sync_status", result.status)
    put("synced_events", result.syncedEvents)
    put("pending_events", result.pendingEvents)
    result.message?.let { put("message", it) }
  }

  fun telemetryStatusPayload(
    dbPath: Path,
    settings: TelemetrySettings,
    pendingEvents: Int = 0,
    latestError: String? = null,
  ): Map<String, Any?> {
    val payload = baseStatusPayload(dbPath, settings)
    if (!settings.enabled) {
      return payload
    }
    payload["pending_events"] = pendingEvents
    latestError?.let { payload["latest_error"] = it }
    return payload
  }

  fun autoSyncTelemetry(
    settings: TelemetrySettings,
    outboxRepository: TelemetryOutboxRepository,
    client: TelemetryClient,
    reportFailures: Boolean = false,
    stderr: (String) -> Unit = {},
  ): SyncResult? {
    val result =
      try {
        syncTelemetry(settings, outboxRepository, client)
      } catch (error: IllegalArgumentException) {
        if (reportFailures) {
          stderr("Telemetry sync skipped: ${error.message}")
        }
        return null
      }
    if (reportFailures && result.status == "failed" && result.message != null) {
      stderr("Telemetry sync failed: ${result.message}")
    }
    return result
  }
}

fun telemetrySyncTarget(settings: TelemetrySettings): String = when {
  !settings.enabled -> "disabled"
  settings.customProxyUrl != null -> "custom_proxy"
  else -> "hosted_relay"
}

private fun syncEnabledTelemetry(
  settings: TelemetrySettings,
  outboxRepository: TelemetryOutboxRepository,
  client: TelemetryClient,
): SyncResult {
  val pendingBefore = outboxRepository.pendingCount()
  val syncContext = syncContext(settings, pendingBefore)
  return when {
    !syncContext.remoteConfigured -> unconfiguredSyncResult(syncContext)
    pendingBefore == 0 -> noopSyncResult(syncContext)
    else -> syncPendingBatches(outboxRepository, settings, client, syncContext)
  }
}

private fun syncPendingBatches(
  outboxRepository: TelemetryOutboxRepository,
  settings: TelemetrySettings,
  client: TelemetryClient,
  syncContext: SyncContext,
): SyncResult {
  var syncedTotal = 0
  while (true) {
    val rows = outboxRepository.listPending(limit = settings.batchSize)
    if (rows.isEmpty()) {
      break
    }
    val eventIds = rows.map { it.id }
    val failureResult =
      trySyncBatch(
        PendingBatch(
          outboxRepository = outboxRepository,
          settings = settings,
          client = client,
          eventIds = eventIds,
          rows = rows,
          syncedTotal = syncedTotal,
          syncContext = syncContext,
        ),
      )
    if (failureResult != null) {
      return failureResult
    }
    syncedTotal += eventIds.size
  }
  return completedSyncResult(syncContext, syncedTotal, outboxRepository.pendingCount())
}

private fun trySyncBatch(batch: PendingBatch): SyncResult? = try {
  batch.client.sendBatch(batch.settings, batch.rows)
  batch.outboxRepository.markSynced(batch.eventIds)
  null
} catch (error: IOException) {
  failedSyncResult(batch, error.message.orEmpty())
} catch (error: IllegalArgumentException) {
  failedSyncResult(batch, error.message.orEmpty())
}

private data class PendingBatch(
  val outboxRepository: TelemetryOutboxRepository,
  val settings: TelemetrySettings,
  val client: TelemetryClient,
  val eventIds: List<Long>,
  val rows: List<TelemetryOutboxRecord>,
  val syncedTotal: Int,
  val syncContext: SyncContext,
)

private fun failedSyncResult(batch: PendingBatch, message: String): SyncResult {
  batch.outboxRepository.markFailed(batch.eventIds, message)
  return syncResult(
    status = "failed",
    syncedEvents = batch.syncedTotal,
    pendingEvents = batch.outboxRepository.pendingCount(),
    syncContext = batch.syncContext,
    message = message,
  )
}
