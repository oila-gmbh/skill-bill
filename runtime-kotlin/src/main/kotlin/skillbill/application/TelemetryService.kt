package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TelemetryConfigMutations
import skillbill.telemetry.TelemetrySettings
import skillbill.telemetry.TelemetrySyncRuntime

@Inject
class TelemetryService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val configStore: TelemetryConfigStore,
  private val telemetryClient: TelemetryClient,
) {
  fun isEnabled(): Boolean = telemetrySettingsOrNull(settingsProvider)?.enabled ?: false

  fun status(dbOverride: String?): Map<String, Any?> {
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
        database.transaction(dbOverride) { unitOfWork ->
          TelemetrySyncRuntime.syncTelemetry(settings, unitOfWork.telemetryOutbox, telemetryClient)
        }
      }
    return TelemetrySyncPayload(
      exitCode = if (result.status == "failed") 1 else 0,
      payload = TelemetrySyncRuntime.syncResultPayload(result),
    )
  }

  fun setLevel(level: String, dbOverride: String?): Map<String, Any?> {
    val (settings, clearedEvents) = setTelemetryLevel(level, dbOverride)
    return telemetryMutationPayload(settings, clearedEvents)
  }

  fun capabilities(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    putAll(telemetryClient.fetchProxyCapabilities(loadTelemetrySettings(settingsProvider)))
  }

  fun remoteStats(
    workflow: String,
    since: String,
    dateFrom: String,
    dateTo: String,
    groupBy: String,
  ): Map<String, Any?> = remoteStats(
    RemoteStatsRequest(mapWorkflow(workflow), since, dateFrom, dateTo, groupBy),
  )

  fun remoteStats(request: RemoteStatsRequest): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    putAll(telemetryClient.fetchRemoteStats(loadTelemetrySettings(settingsProvider), request))
  }

  private fun setTelemetryLevel(level: String, dbOverride: String?): Pair<TelemetrySettings, Int> =
    if (level == "off" && database.databaseExists(dbOverride)) {
      database.transaction(dbOverride) { unitOfWork ->
        TelemetryConfigMutations.setTelemetryLevel(
          level = level,
          configStore = configStore,
          settingsProvider = settingsProvider,
          outbox = unitOfWork.telemetryOutbox,
        )
      }
    } else {
      TelemetryConfigMutations.setTelemetryLevel(
        level = level,
        configStore = configStore,
        settingsProvider = settingsProvider,
      )
    }
}

data class TelemetrySyncPayload(
  val exitCode: Int,
  val payload: Map<String, Any?>,
)
