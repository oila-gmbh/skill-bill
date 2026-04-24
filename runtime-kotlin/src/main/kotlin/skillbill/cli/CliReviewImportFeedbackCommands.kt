package skillbill.cli

import skillbill.cli.models.ImportReviewArgs
import skillbill.cli.models.RecordFeedbackArgs
import skillbill.db.DatabaseRuntime
import skillbill.review.FeedbackRequest
import skillbill.review.ReviewRuntime
import skillbill.review.ReviewStatsRuntime
import skillbill.review.TriageRuntime

internal val reviewImportFeedbackCliCommands: List<CliCommandNode> =
  listOf(
    leafCommand(
      name = "import-review",
      parse = ::parseImportReviewArgs,
      execute = ::importReviewCommand,
    ),
    leafCommand(
      name = "record-feedback",
      parse = ::parseRecordFeedbackArgs,
      execute = ::recordFeedbackCommand,
    ),
  )

private fun parseImportReviewArgs(cursor: ArgumentCursor): ImportReviewArgs = ImportReviewArgs(
  input = cursor.take(),
  format = parseSingleFormat(cursor, "import-review"),
)

private fun importReviewCommand(
  args: ImportReviewArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  val (text, sourcePath) = ReviewRuntime.readInput(args.input, context.stdinText)
  val review = ReviewRuntime.parseReview(text)
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    ReviewRuntime.saveImportedReview(openDb.connection, review, sourcePath)
    if (review.findings.isEmpty()) {
      val settings = telemetrySettingsOrNull(context)
      ReviewStatsRuntime.updateReviewFinishedTelemetryState(
        openDb.connection,
        review.reviewRunId,
        enabled = settings?.enabled ?: false,
        level = settings?.level ?: "off",
      )
    }
    return payloadResult(
      linkedMapOf(
        "db_path" to openDb.dbPath.toString(),
        "review_run_id" to review.reviewRunId,
        "review_session_id" to review.reviewSessionId,
        "finding_count" to review.findings.size,
        "routed_skill" to review.routedSkill,
        "detected_scope" to review.detectedScope,
        "detected_stack" to review.detectedStack,
        "execution_mode" to review.executionMode,
      ),
      args.format,
    )
  }
}

private fun parseRecordFeedbackArgs(cursor: ArgumentCursor): RecordFeedbackArgs {
  var runId: String? = null
  var event: String? = null
  var note = ""
  val findings = mutableListOf<String>()
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--run-id" -> runId = cursor.requireValue(token)
      "--event" -> event = cursor.requireValue(token)
      "--finding" -> findings += cursor.requireValue(token)
      "--note" -> note = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for record-feedback.")
    }
  }
  require(!runId.isNullOrBlank()) { "--run-id is required." }
  require(!event.isNullOrBlank()) { "--event is required." }
  require(findings.isNotEmpty()) { "At least one --finding is required." }
  return RecordFeedbackArgs(runId, event, findings.toList(), note, format)
}

private fun recordFeedbackCommand(
  args: RecordFeedbackArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    TriageRuntime.recordFeedback(
      openDb.connection,
      FeedbackRequest(args.runId, args.findings, args.event, args.note),
      feedbackTelemetryOptions(context),
    )
    return payloadResult(
      linkedMapOf(
        "db_path" to openDb.dbPath.toString(),
        "review_run_id" to args.runId,
        "outcome_type" to args.event,
        "recorded_findings" to args.findings.size,
      ),
      args.format,
    )
  }
}
