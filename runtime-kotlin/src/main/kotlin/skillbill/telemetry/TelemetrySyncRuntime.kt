package skillbill.telemetry

import skillbill.db.DatabaseRuntime
import skillbill.db.TelemetryOutboxRow
import skillbill.db.TelemetryOutboxStore
import java.io.IOException
import java.nio.file.Path

object TelemetrySyncRuntime {
  fun syncTelemetry(
    dbPath: Path,
    settings: TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(),
    requester: HttpRequester = TelemetryHttpRuntime.defaultHttpRequester,
  ): SyncResult = if (!settings.enabled) {
    disabledSyncResult(settings)
  } else {
    syncEnabledTelemetry(dbPath, settings, requester)
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
    settings: TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(),
  ): Map<String, Any?> {
    val payload = baseStatusPayload(dbPath, settings)
    if (!settings.enabled) {
      return payload
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxStore = TelemetryOutboxStore(connection)
      payload["pending_events"] = outboxStore.pendingCount()
      outboxStore.latestError()?.let { payload["latest_error"] = it }
    }
    return payload
  }

  fun autoSyncTelemetry(
    dbPath: Path,
    reportFailures: Boolean = false,
    stderr: (String) -> Unit = {},
    settings: TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(),
    requester: HttpRequester = TelemetryHttpRuntime.defaultHttpRequester,
  ): SyncResult? {
    val result =
      try {
        syncTelemetry(dbPath, settings, requester)
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

private fun syncEnabledTelemetry(dbPath: Path, settings: TelemetrySettings, requester: HttpRequester): SyncResult =
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    val outboxStore = TelemetryOutboxStore(connection)
    val pendingBefore = outboxStore.pendingCount()
    val syncContext = syncContext(settings, pendingBefore)
    when {
      !syncContext.remoteConfigured -> unconfiguredSyncResult(syncContext)
      pendingBefore == 0 -> noopSyncResult(syncContext)
      else -> syncPendingBatches(outboxStore, settings, requester, syncContext)
    }
  }

private fun syncPendingBatches(
  outboxStore: TelemetryOutboxStore,
  settings: TelemetrySettings,
  requester: HttpRequester,
  syncContext: SyncContext,
): SyncResult {
  var syncedTotal = 0
  while (true) {
    val rows = outboxStore.listPending(limit = settings.batchSize)
    if (rows.isEmpty()) {
      break
    }
    val eventIds = rows.map { it.id }
    val failureResult =
      trySyncBatch(
        PendingBatch(
          outboxStore = outboxStore,
          settings = settings,
          requester = requester,
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
  return completedSyncResult(syncContext, syncedTotal, outboxStore.pendingCount())
}

private fun trySyncBatch(batch: PendingBatch): SyncResult? = try {
  TelemetryHttpRuntime.sendProxyBatch(batch.settings, batch.rows, batch.requester)
  batch.outboxStore.markSynced(batch.eventIds)
  null
} catch (error: IOException) {
  failedSyncResult(batch, error.message.orEmpty())
} catch (error: IllegalArgumentException) {
  failedSyncResult(batch, error.message.orEmpty())
}

private data class PendingBatch(
  val outboxStore: TelemetryOutboxStore,
  val settings: TelemetrySettings,
  val requester: HttpRequester,
  val eventIds: List<Long>,
  val rows: List<TelemetryOutboxRow>,
  val syncedTotal: Int,
  val syncContext: SyncContext,
)

private fun failedSyncResult(batch: PendingBatch, message: String): SyncResult {
  batch.outboxStore.markFailed(batch.eventIds, message)
  return syncResult(
    status = "failed",
    syncedEvents = batch.syncedTotal,
    pendingEvents = batch.outboxStore.pendingCount(),
    syncContext = batch.syncContext,
    message = message,
  )
}
