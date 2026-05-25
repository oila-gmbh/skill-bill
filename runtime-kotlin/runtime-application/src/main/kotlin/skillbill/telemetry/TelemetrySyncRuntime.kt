package skillbill.telemetry

import skillbill.application.model.TelemetryStatusResult
import skillbill.application.model.TelemetrySyncStatusResult
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.TelemetryClient
import skillbill.telemetry.model.SyncResult
import skillbill.telemetry.model.TelemetrySettings
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

  fun syncResult(result: SyncResult): TelemetrySyncStatusResult = TelemetrySyncStatusResult(
    configPath = result.configPath.toString(),
    telemetryEnabled = result.telemetryEnabled,
    telemetryLevel = result.telemetryLevel,
    syncTarget = telemetrySyncTarget(result),
    remoteConfigured = result.remoteConfigured,
    proxyConfigured = result.proxyConfigured,
    proxyUrl = result.proxyUrl,
    customProxyUrl = result.customProxyUrl,
    syncStatus = result.status,
    syncedEvents = result.syncedEvents,
    pendingEvents = result.pendingEvents,
    message = result.message,
  )

  fun telemetryStatusPayload(
    dbPath: Path,
    settings: TelemetrySettings,
    pendingEvents: Int = 0,
    latestError: String? = null,
  ): TelemetryStatusResult {
    val result = baseStatusResult(dbPath, settings)
    if (!settings.enabled) {
      return result
    }
    return result.copy(
      pendingEvents = pendingEvents,
      latestError = latestError,
    )
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
