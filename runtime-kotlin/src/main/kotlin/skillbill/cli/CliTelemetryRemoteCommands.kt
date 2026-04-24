package skillbill.cli

import skillbill.cli.models.TelemetryStatsArgs
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TelemetryHttpRuntime

internal val telemetryRemoteCliCommands: List<CliCommandNode> =
  listOf(
    leafCommand(
      name = "capabilities",
      parse = ::parseTelemetryCapabilitiesArgs,
      execute = { args, context, _ -> telemetryCapabilitiesCommand(args, context) },
    ),
    leafCommand(
      name = "stats",
      parse = ::parseTelemetryStatsArgs,
      execute = { args, context, _ -> telemetryStatsCommand(args, context) },
    ),
  )

private fun parseTelemetryCapabilitiesArgs(cursor: ArgumentCursor): FormatOnlyArgs =
  parseFormatOnlyArgs(cursor, "telemetry capabilities")

private fun telemetryCapabilitiesCommand(args: FormatOnlyArgs, context: CliRuntimeContext): CliExecutionResult {
  val payload =
    TelemetryHttpRuntime.fetchProxyCapabilities(
      settings = loadTelemetrySettings(context),
      requester = context.requester,
      environment = context.environment,
    )
  return payloadResult(linkedMapOf<String, Any?>().apply { putAll(payload) }, args.format)
}

private fun parseTelemetryStatsArgs(cursor: ArgumentCursor): TelemetryStatsArgs {
  val workflow = cursor.take()
  var since = ""
  var dateFrom = ""
  var dateTo = ""
  var groupBy = ""
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--since" -> since = cursor.requireValue(token)
      "--date-from" -> dateFrom = cursor.requireValue(token)
      "--date-to" -> dateTo = cursor.requireValue(token)
      "--group-by" -> groupBy = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for telemetry stats.")
    }
  }
  return TelemetryStatsArgs(workflow, since, dateFrom, dateTo, groupBy, format)
}

private fun telemetryStatsCommand(args: TelemetryStatsArgs, context: CliRuntimeContext): CliExecutionResult {
  val request = RemoteStatsRequest(mapWorkflow(args.workflow), args.since, args.dateFrom, args.dateTo, args.groupBy)
  val payload =
    skillbill.telemetry.TelemetryRemoteStatsRuntime.fetchRemoteStats(
      request = request,
      settings = loadTelemetrySettings(context),
      requester = context.requester,
      environment = context.environment,
    )
  return payloadResult(linkedMapOf<String, Any?>().apply { putAll(payload) }, args.format)
}
