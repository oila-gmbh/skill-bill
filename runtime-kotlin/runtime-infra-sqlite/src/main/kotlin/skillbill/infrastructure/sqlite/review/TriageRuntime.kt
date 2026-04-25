package skillbill.infrastructure.sqlite.review

import skillbill.review.FeedbackRequest
import skillbill.review.FeedbackTelemetryOptions
import skillbill.review.NumberedFinding
import skillbill.review.TriageDecision
import skillbill.review.TriageDecisionParser
import java.sql.Connection

object TriageRuntime {
  fun expandBulkDecisions(rawDecisions: List<String>, numberedFindings: List<NumberedFinding>): List<String> =
    TriageDecisionParser.expandBulkDecisions(rawDecisions, numberedFindings)

  fun expandStructuredDecision(rawDecision: String): List<String>? =
    TriageDecisionParser.expandStructuredDecision(rawDecision)

  fun parseTriageDecisions(rawDecisions: List<String>, numberedFindings: List<NumberedFinding>): List<TriageDecision> =
    TriageDecisionParser.parseTriageDecisions(rawDecisions, numberedFindings)

  fun normalizeTriageAction(rawAction: String): String = TriageDecisionParser.normalizeTriageAction(rawAction)

  fun normalizeTriageNote(rawNote: String?): String = TriageDecisionParser.normalizeTriageNote(rawNote)

  fun recordFeedback(
    connection: Connection,
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions = FeedbackTelemetryOptions(),
  ): Map<String, Any?>? = connection.inTransaction {
    recordFeedbackWithoutTransaction(connection, request, telemetryOptions)
  }

  fun recordFeedbackWithoutTransaction(
    connection: Connection,
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions = FeedbackTelemetryOptions(),
  ): Map<String, Any?>? {
    validateFeedbackRequest(connection, request)
    request.findingIds.forEach { findingId ->
      insertFeedbackEvent(connection, request.reviewRunId, findingId, request.eventType, request.note)
    }
    return ReviewStatsRuntime.updateReviewFinishedTelemetryState(
      connection = connection,
      reviewRunId = request.reviewRunId,
      enabled = telemetryOptions.enabled ?: false,
      level = telemetryOptions.level ?: "off",
    )
  }
}
private const val FEEDBACK_EVENT_TYPE_PARAM_INDEX: Int = 3
private const val FEEDBACK_NOTE_PARAM_INDEX: Int = 4

private fun validateFeedbackRequest(connection: Connection, request: FeedbackRequest) {
  require(ReviewRuntime.reviewExists(connection, request.reviewRunId)) {
    "Unknown review run id '${request.reviewRunId}'. Import the review first."
  }
  val missingFindings =
    request.findingIds.filterNot { findingId ->
      ReviewRuntime.findingExists(connection, request.reviewRunId, findingId)
    }.sorted()
  require(missingFindings.isEmpty()) {
    "Unknown finding ids for review run '${request.reviewRunId}': ${missingFindings.joinToString(", ")}"
  }
}

private fun insertFeedbackEvent(
  connection: Connection,
  reviewRunId: String,
  findingId: String,
  eventType: String,
  note: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feedback_events (review_run_id, finding_id, event_type, note)
    VALUES (?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, reviewRunId)
    statement.setString(2, findingId)
    statement.setString(FEEDBACK_EVENT_TYPE_PARAM_INDEX, eventType)
    statement.setString(FEEDBACK_NOTE_PARAM_INDEX, note)
    statement.executeUpdate()
  }
}

private fun <T> Connection.inTransaction(block: () -> T): T {
  val previousAutoCommit = autoCommit
  autoCommit = false
  try {
    val outcome = runCatching(block)
    if (outcome.isSuccess) {
      commit()
    } else {
      rollback()
    }
    return outcome.getOrThrow()
  } finally {
    autoCommit = previousAutoCommit
  }
}
