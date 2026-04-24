package skillbill.cli

import skillbill.cli.models.ReviewStatsArgs
import skillbill.review.ReviewStatsRuntime

internal val reviewStatsCliCommands: List<CliCommandNode> =
  listOf(
    leafCommand(
      name = "stats",
      parse = ::parseReviewStatsArgs,
      execute = ::reviewStatsCommand,
    ),
    leafCommand(
      name = "implement-stats",
      aliases = setOf("feature-implement-stats"),
      parse = { cursor -> parseFormatOnlyArgs(cursor, "implement-stats") },
      execute = ::featureImplementStatsCommand,
    ),
    leafCommand(
      name = "verify-stats",
      aliases = setOf("feature-verify-stats"),
      parse = { cursor -> parseFormatOnlyArgs(cursor, "verify-stats") },
      execute = ::featureVerifyStatsCommand,
    ),
  )

private fun parseReviewStatsArgs(cursor: ArgumentCursor): ReviewStatsArgs {
  var runId: String? = null
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--run-id" -> runId = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for stats.")
    }
  }
  return ReviewStatsArgs(runId, format)
}

private fun reviewStatsCommand(
  args: ReviewStatsArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult = featureStatsPayloadResult(dbOverride, context, args.format) { connection ->
  ReviewStatsRuntime.statsPayload(connection, args.runId)
}

private fun featureImplementStatsCommand(
  args: FormatOnlyArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult = featureStatsPayloadResult(dbOverride, context, args.format) { connection ->
  ReviewStatsRuntime.featureImplementStatsPayload(connection)
}

private fun featureVerifyStatsCommand(
  args: FormatOnlyArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult = featureStatsPayloadResult(dbOverride, context, args.format) { connection ->
  ReviewStatsRuntime.featureVerifyStatsPayload(connection)
}
