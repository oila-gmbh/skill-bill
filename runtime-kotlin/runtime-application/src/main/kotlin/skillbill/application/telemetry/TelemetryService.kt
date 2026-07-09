package skillbill.application.telemetry

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.TelemetryMutationResult
import skillbill.application.model.TelemetryStatusResult
import skillbill.application.model.TelemetrySyncPayload
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.sync.TelemetrySyncRuntime
import skillbill.telemetry.sync.syncResult

@Inject
class TelemetryService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val configStore: TelemetryConfigStore,
  private val telemetryClient: TelemetryClient,
) {
  fun isEnabled(): Boolean = telemetrySettingsOrNull(settingsProvider)?.enabled ?: false

  fun status(dbOverride: String?): TelemetryStatusResult {
    val dbPath = database.resolveDbPath(dbOverride)
    val settings = loadTelemetrySettings(settingsProvider)
    if (!settings.enabled) {
      return TelemetrySyncRuntime.telemetryStatusPayload(dbPath, settings)
    }
    return database.read(dbOverride) { unitOfWork ->
      TelemetrySyncRuntime.telemetryStatusPayload(
        dbPath = unitOfWork.dbPath,
        settings = settings,
        pendingEvents = unitOfWork.telemetryOutbox.pendingCount(),
        latestError = unitOfWork.telemetryOutbox.latestError(),
      )
    }
  }

  fun sync(dbOverride: String?): TelemetrySyncPayload {
    val settings = loadTelemetrySettings(settingsProvider)
    val result =
      if (!settings.enabled) {
        TelemetrySyncRuntime.disabledSync(settings)
      } else {
        TelemetrySyncRuntime.syncTelemetry(
          settings,
          sessionTelemetryOutboxRepository(database, dbOverride),
          telemetryClient,
        )
      }
    return TelemetrySyncPayload(
      exitCode = if (result.status == "failed") 1 else 0,
      result = TelemetrySyncRuntime.syncResult(result),
    )
  }

  fun autoSync(dbOverride: String? = null) {
    val settings = telemetrySettingsOrNull(settingsProvider)
    if (settings == null || !settings.enabled || !database.databaseExists(dbOverride)) return
    database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.telemetryReconciliation.reconcileStaleSessions(settings.level)
    }
    TelemetrySyncRuntime.autoSyncTelemetry(
      settings,
      sessionTelemetryOutboxRepository(database, dbOverride),
      telemetryClient,
    )
  }

  fun setLevel(level: String, dbOverride: String?): TelemetryMutationResult {
    val result = TelemetryLevelMutationService(database, settingsProvider, configStore).setLevel(level, dbOverride)
    val settings = result.settings
    val clearedEvents = result.clearedEvents
    return telemetryMutationResult(settings, clearedEvents)
  }

  fun capabilities(): TelemetryProxyCapabilities = telemetryClient.fetchProxyCapabilities(
    loadTelemetrySettings(settingsProvider),
  )

  fun remoteStats(
    workflow: String,
    since: String,
    dateFrom: String,
    dateTo: String,
    groupBy: String,
  ): TelemetryRemoteStatsResult = remoteStats(
    RemoteStatsRequest(mapWorkflow(workflow), since, dateFrom, dateTo, groupBy),
  )

  fun remoteStats(request: RemoteStatsRequest): TelemetryRemoteStatsResult =
    telemetryClient.fetchRemoteStats(loadTelemetrySettings(settingsProvider), request)

  fun captureException(workflowPhase: String, error: Exception, dbOverride: String? = null) {
    if (!database.databaseExists(dbOverride)) return
    runCatching {
      enqueueRuntimeException(sessionTelemetryOutboxRepository(database, dbOverride), workflowPhase, error)
    }
  }
}

private fun sessionTelemetryOutboxRepository(
  database: DatabaseSessionFactory,
  dbOverride: String?,
): TelemetryOutboxRepository = object : TelemetryOutboxRepository {
  override fun enqueue(eventName: String, payloadJson: String): Long =
    database.transaction(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.enqueue(eventName, payloadJson) }

  override fun listPending(limit: Int?): List<TelemetryOutboxRecord> =
    database.read(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.listPending(limit) }

  override fun pendingCount(): Int =
    database.read(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.pendingCount() }

  override fun latestError(): String? =
    database.read(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.latestError() }

  override fun markSynced(id: Long, syncedAt: String) {
    database.transaction(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.markSynced(id, syncedAt) }
  }

  override fun markSynced(eventIds: List<Long>) {
    database.transaction(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.markSynced(eventIds) }
  }

  override fun markFailed(id: Long, lastError: String) {
    database.transaction(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.markFailed(id, lastError) }
  }

  override fun markFailed(eventIds: List<Long>, lastError: String) {
    database.transaction(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.markFailed(eventIds, lastError) }
  }

  override fun clear(): Int = database.transaction(dbOverride) { unitOfWork -> unitOfWork.telemetryOutbox.clear() }
}
