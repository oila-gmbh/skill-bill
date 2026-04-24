package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.db.DatabaseRuntime
import skillbill.review.FeedbackRequest
import skillbill.review.NumberedFinding
import skillbill.review.ReviewRuntime
import skillbill.review.ReviewStatsRuntime
import skillbill.review.TriageRuntime
import java.sql.Connection

@Inject
class ReviewService(private val context: RuntimeContext) {
  fun importReview(input: String, dbOverride: String?): Map<String, Any?> {
    val (text, sourcePath) = ReviewRuntime.readInput(input, context.stdinText)
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
      return linkedMapOf(
        "db_path" to openDb.dbPath.toString(),
        "review_run_id" to review.reviewRunId,
        "review_session_id" to review.reviewSessionId,
        "finding_count" to review.findings.size,
        "routed_skill" to review.routedSkill,
        "detected_scope" to review.detectedScope,
        "detected_stack" to review.detectedStack,
        "execution_mode" to review.executionMode,
      )
    }
  }

  fun recordFeedback(
    runId: String,
    event: String,
    findings: List<String>,
    note: String,
    dbOverride: String?,
  ): Map<String, Any?> {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      TriageRuntime.recordFeedback(
        openDb.connection,
        FeedbackRequest(runId, findings, event, note),
        feedbackTelemetryOptions(context),
      )
      return linkedMapOf(
        "db_path" to openDb.dbPath.toString(),
        "review_run_id" to runId,
        "outcome_type" to event,
        "recorded_findings" to findings.size,
      )
    }
  }

  fun triage(runId: String, decisions: List<String>, listOnly: Boolean, dbOverride: String?): TriageResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val numberedFindings = ReviewRuntime.fetchNumberedFindings(openDb.connection, runId)
      if (listOnly || decisions.isEmpty()) {
        val findings = numberedFindings.map(::findingPayload)
        return TriageResult(
          payload =
          linkedMapOf(
            "db_path" to openDb.dbPath.toString(),
            "review_run_id" to runId,
            "findings" to findings,
          ),
          findings = findings,
        )
      }
      val recorded = applyTriageDecisions(openDb.connection, runId, numberedFindings, decisions)
      return TriageResult(
        payload =
        linkedMapOf(
          "db_path" to openDb.dbPath.toString(),
          "review_run_id" to runId,
          "recorded" to recorded,
        ),
        recorded = recorded,
      )
    }
  }

  fun reviewStats(runId: String?, dbOverride: String?): Map<String, Any?> =
    statsPayload(dbOverride) { connection -> ReviewStatsRuntime.statsPayload(connection, runId) }

  fun featureImplementStats(dbOverride: String?): Map<String, Any?> =
    statsPayload(dbOverride, ReviewStatsRuntime::featureImplementStatsPayload)

  fun featureVerifyStats(dbOverride: String?): Map<String, Any?> =
    statsPayload(dbOverride, ReviewStatsRuntime::featureVerifyStatsPayload)

  private fun statsPayload(dbOverride: String?, payloadBuilder: (Connection) -> Map<String, Any?>): Map<String, Any?> =
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      linkedMapOf<String, Any?>().apply {
        putAll(payloadBuilder(openDb.connection))
        put("db_path", openDb.dbPath.toString())
      }
    }

  private fun applyTriageDecisions(
    connection: Connection,
    runId: String,
    numberedFindings: List<NumberedFinding>,
    decisions: List<String>,
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
}

data class TriageResult(
  val payload: Map<String, Any?>,
  val findings: List<Map<String, Any?>> = emptyList(),
  val recorded: List<Map<String, Any?>> = emptyList(),
)
