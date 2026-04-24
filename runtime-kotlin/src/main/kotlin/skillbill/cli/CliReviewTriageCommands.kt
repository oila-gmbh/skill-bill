package skillbill.cli

import skillbill.cli.models.TriageRequest
import skillbill.db.DatabaseRuntime
import skillbill.review.FeedbackRequest
import skillbill.review.NumberedFinding
import skillbill.review.ReviewRuntime
import skillbill.review.TriageRuntime

internal val triageCliCommand: CliCommandNode =
  leafCommand(
    name = "triage",
    parse = ::parseTriageRequest,
    execute = ::triageCommand,
  )

private fun parseTriageRequest(cursor: ArgumentCursor): TriageRequest {
  var runId: String? = null
  val decisions = mutableListOf<String>()
  var listOnly = false
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--run-id" -> runId = cursor.requireValue(token)
      "--decision" -> decisions += cursor.requireValue(token)
      "--list" -> listOnly = true
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for triage.")
    }
  }
  require(!runId.isNullOrBlank()) { "--run-id is required." }
  return TriageRequest(runId, decisions, listOnly, format)
}

private fun triageCommand(
  request: TriageRequest,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    val numberedFindings = ReviewRuntime.fetchNumberedFindings(openDb.connection, request.runId)
    if (request.listOnly || request.decisions.isEmpty()) {
      val findings = numberedFindings.map(::findingPayload)
      val payload =
        linkedMapOf(
          "db_path" to openDb.dbPath.toString(),
          "review_run_id" to request.runId,
          "findings" to findings,
        )
      return if (request.format == CliFormat.JSON) {
        payloadResult(payload, request.format)
      } else {
        CliExecutionResult(
          exitCode = 0,
          stdout = CliOutput.numberedFindings(request.runId, findings),
          payload = payload,
        )
      }
    }
    val recorded = applyTriageDecisions(openDb.connection, request.runId, numberedFindings, request.decisions, context)
    val payload =
      linkedMapOf(
        "db_path" to openDb.dbPath.toString(),
        "review_run_id" to request.runId,
        "recorded" to recorded,
      )
    return if (request.format == CliFormat.JSON) {
      payloadResult(payload, request.format)
    } else {
      CliExecutionResult(
        exitCode = 0,
        stdout = CliOutput.triageResult(request.runId, recorded),
        payload = payload,
      )
    }
  }
}

private fun applyTriageDecisions(
  connection: java.sql.Connection,
  runId: String,
  numberedFindings: List<NumberedFinding>,
  decisions: List<String>,
  context: CliRuntimeContext,
): List<Map<String, Any?>> {
  val parsedDecisions = TriageRuntime.parseTriageDecisions(decisions, numberedFindings)
  parsedDecisions.forEach { decision ->
    TriageRuntime.recordFeedback(
      connection,
      FeedbackRequest(runId, listOf(decision.findingId), decision.outcomeType, decision.note),
      feedbackTelemetryOptions(context),
    )
  }
  return parsedDecisions.map { decision ->
    linkedMapOf(
      "number" to decision.number,
      "finding_id" to decision.findingId,
      "outcome_type" to decision.outcomeType,
      "note" to decision.note,
    )
  }
}
