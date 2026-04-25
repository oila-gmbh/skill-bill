package skillbill.infrastructure.sqlite.review

import skillbill.review.FindingOutcomeRow
import skillbill.review.ReviewSummary
import java.sql.Connection

object ReviewStatsRuntime {
  fun statsPayload(connection: Connection, reviewRunId: String?): Map<String, Any?> {
    if (reviewRunId != null) {
      require(ReviewRuntime.reviewExists(connection, reviewRunId)) {
        "Unknown review run id '$reviewRunId'."
      }
    }
    return summarizeFindingRows(queryLatestFindingOutcomes(connection, reviewRunId)) +
      mapOf("review_run_id" to reviewRunId)
  }

  fun featureVerifyStatsPayload(connection: Connection): Map<String, Any?> =
    buildFeatureVerifyStatsPayload(loadRows(connection, "feature_verify_sessions"))

  fun featureImplementStatsPayload(connection: Connection): Map<String, Any?> =
    buildFeatureImplementStatsPayload(loadRows(connection, "feature_implement_sessions"))

  fun clearReviewFinishedTelemetryState(connection: Connection, reviewRunId: String) {
    connection.prepareStatement(
      """
      UPDATE review_runs
      SET review_finished_at = NULL,
          review_finished_event_emitted_at = NULL
      WHERE review_run_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeUpdate()
    }
  }

  fun buildReviewFinishedPayload(
    connection: Connection,
    reviewRunId: String,
    reviewSummary: ReviewSummary? = null,
    findingRows: List<FindingOutcomeRow>? = null,
    level: String = "anonymous",
  ): Map<String, Any?> = reviewFinishedPayload(
    connection = connection,
    reviewSummary = reviewSummary ?: ReviewRuntime.fetchReviewSummary(connection, reviewRunId),
    findingRows = findingRows ?: queryLatestFindingOutcomes(connection, reviewRunId),
    level = level,
  )

  fun updateReviewFinishedTelemetryState(
    connection: Connection,
    reviewRunId: String,
    enabled: Boolean? = null,
    level: String? = null,
  ): Map<String, Any?>? {
    val telemetryState = resolveTelemetryState(enabled, level)
    var reviewSummary = ReviewRuntime.fetchReviewSummary(connection, reviewRunId)
    val findingRows = queryLatestFindingOutcomes(connection, reviewRunId)
    val alreadyEmitted =
      reviewAlreadyEmittedForSession(connection, reviewSummary.reviewSessionId.orEmpty(), reviewRunId)
    return if (alreadyEmitted) {
      null
    } else if (shouldSkipReviewFinishedTelemetry(findingRows, reviewSummary)) {
      clearReviewFinishedTelemetryState(connection, reviewRunId)
      null
    } else {
      reviewSummary = ensureReviewFinishedTimestamp(connection, reviewRunId, reviewSummary)
      val payload =
        reviewFinishedPayload(
          connection = connection,
          reviewSummary = reviewSummary,
          findingRows = findingRows,
          level = telemetryState.level,
        )
      finalizeReviewFinishedTelemetry(
        connection = connection,
        reviewRunId = reviewRunId,
        reviewSummary = reviewSummary,
        payload = payload,
        telemetryEnabled = telemetryState.enabled,
      )
    }
  }
}
