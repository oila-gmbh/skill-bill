package skillbill.cli

import skillbill.cli.models.TelemetryEnableArgs
import skillbill.cli.models.TelemetrySetLevelArgs
import skillbill.db.DatabaseRuntime
import skillbill.telemetry.TelemetrySyncRuntime

internal val telemetryLocalCliCommands: List<CliCommandNode> =
  listOf(
    leafCommand(name = "status", parse = ::parseTelemetryStatusArgs, execute = ::telemetryStatusCommand),
    leafCommand(name = "sync", parse = ::parseTelemetrySyncArgs, execute = ::telemetrySyncCommand),
    leafCommand(name = "enable", parse = ::parseTelemetryEnableArgs, execute = ::telemetryEnableCommand),
    leafCommand(name = "disable", parse = ::parseTelemetryDisableArgs, execute = ::telemetryDisableCommand),
    leafCommand(name = "set-level", parse = ::parseTelemetrySetLevelArgs, execute = ::telemetrySetLevelCommand),
  )

private fun parseTelemetryStatusArgs(cursor: ArgumentCursor): FormatOnlyArgs =
  parseFormatOnlyArgs(cursor, "telemetry status")

private fun telemetryStatusCommand(
  args: FormatOnlyArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
  return payloadResult(
    TelemetrySyncRuntime.telemetryStatusPayload(dbPath, loadTelemetrySettings(context)),
    args.format,
  )
}

private fun parseTelemetrySyncArgs(cursor: ArgumentCursor): FormatOnlyArgs =
  parseFormatOnlyArgs(cursor, "telemetry sync")

private fun telemetrySyncCommand(
  args: FormatOnlyArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
  val result = TelemetrySyncRuntime.syncTelemetry(dbPath, loadTelemetrySettings(context), context.requester)
  val payload = TelemetrySyncRuntime.syncResultPayload(result)
  return CliExecutionResult(
    exitCode = if (result.status == "failed") 1 else 0,
    stdout = CliOutput.emit(payload, args.format),
    payload = payload,
  )
}

private fun parseTelemetryEnableArgs(cursor: ArgumentCursor): TelemetryEnableArgs {
  var level = "anonymous"
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--level" -> level = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for telemetry enable.")
    }
  }
  return TelemetryEnableArgs(level, format)
}

private fun telemetryEnableCommand(
  args: TelemetryEnableArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult = telemetryMutationResult(args.level, context, dbOverride, args.format)

private fun parseTelemetryDisableArgs(cursor: ArgumentCursor): FormatOnlyArgs =
  parseFormatOnlyArgs(cursor, "telemetry disable")

private fun telemetryDisableCommand(
  args: FormatOnlyArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult = telemetryMutationResult("off", context, dbOverride, args.format)

private fun parseTelemetrySetLevelArgs(cursor: ArgumentCursor): TelemetrySetLevelArgs = TelemetrySetLevelArgs(
  level = cursor.take(),
  format = parseFormat(cursor),
)

private fun telemetrySetLevelCommand(
  args: TelemetrySetLevelArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult = telemetryMutationResult(args.level, context, dbOverride, args.format)
