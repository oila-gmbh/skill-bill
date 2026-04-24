package skillbill.app

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.db.DatabaseRuntime
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TelemetryConfigMutationRuntime
import skillbill.telemetry.TelemetryHttpRuntime
import skillbill.telemetry.TelemetryRemoteStatsRuntime
import skillbill.telemetry.TelemetrySyncRuntime

@Inject
class TelemetryService(private val context: RuntimeContext) {
  fun status(dbOverride: String?): Map<String, Any?> {
    val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
    return TelemetrySyncRuntime.telemetryStatusPayload(dbPath, loadTelemetrySettings(context))
  }

  fun sync(dbOverride: String?): TelemetrySyncPayload {
    val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
    val result = TelemetrySyncRuntime.syncTelemetry(dbPath, loadTelemetrySettings(context), context.requester)
    return TelemetrySyncPayload(
      exitCode = if (result.status == "failed") 1 else 0,
      payload = TelemetrySyncRuntime.syncResultPayload(result),
    )
  }

  fun setLevel(level: String, dbOverride: String?): Map<String, Any?> {
    val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
    val (settings, clearedEvents) =
      TelemetryConfigMutationRuntime.setTelemetryLevel(
        level = level,
        dbPath = dbPath,
        environment = context.environment,
        userHome = context.userHome,
      )
    return telemetryMutationPayload(settings, clearedEvents)
  }

  fun capabilities(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    putAll(
      TelemetryHttpRuntime.fetchProxyCapabilities(
        settings = loadTelemetrySettings(context),
        requester = context.requester,
        environment = context.environment,
      ),
    )
  }

  fun remoteStats(
    workflow: String,
    since: String,
    dateFrom: String,
    dateTo: String,
    groupBy: String,
  ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    putAll(
      TelemetryRemoteStatsRuntime.fetchRemoteStats(
        request = RemoteStatsRequest(mapWorkflow(workflow), since, dateFrom, dateTo, groupBy),
        settings = loadTelemetrySettings(context),
        requester = context.requester,
        environment = context.environment,
      ),
    )
  }
}

data class TelemetrySyncPayload(
  val exitCode: Int,
  val payload: Map<String, Any?>,
)
