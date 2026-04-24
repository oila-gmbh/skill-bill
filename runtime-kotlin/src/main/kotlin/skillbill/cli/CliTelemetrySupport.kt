package skillbill.cli

import skillbill.db.DatabaseRuntime
import skillbill.telemetry.TelemetryConfigMutationRuntime

internal fun telemetryMutationResult(
  level: String,
  context: CliRuntimeContext,
  dbOverride: String?,
  format: CliFormat,
): CliExecutionResult {
  val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
  val (settings, clearedEvents) =
    TelemetryConfigMutationRuntime.setTelemetryLevel(
      level = level,
      dbPath = dbPath,
      environment = context.environment,
      userHome = context.userHome,
    )
  return payloadResult(telemetryMutationPayload(settings, clearedEvents), format)
}
