package skillbill.infrastructure.sqlite.review

import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FindingOutcomeRow
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.ReviewSummary
import java.sql.Connection

object ReviewStatsRuntime {
  fun statsSnapshot(connection: Connection, reviewRunId: String?): ReviewRepositoryStatsSnapshot {
    if (reviewRunId != null) {
      require(ReviewRuntime.reviewExists(connection, reviewRunId)) {
        "Unknown review run id '$reviewRunId'."
      }
    }
    return ReviewRepositoryStatsSnapshot(
      reviewRunId = reviewRunId,
      stats = summarizeFindingRows(queryLatestFindingOutcomes(connection, reviewRunId)),
      health = buildReviewHealthStats(connection, reviewRunId),
    )
  }

  fun featureVerifyStats(connection: Connection): FeatureVerifyWorkflowStats =
    buildFeatureVerifyStats(loadRows(connection, "feature_verify_sessions"))

  fun featureImplementStats(connection: Connection): FeatureImplementWorkflowStats =
    buildFeatureImplementStats(loadRows(connection, "feature_implement_sessions"))

  fun featureTaskRuntimeStats(connection: Connection): FeatureTaskRuntimeWorkflowStats =
    buildFeatureTaskRuntimeStats(loadRows(connection, "feature_task_runtime_sessions"))

  fun goalStats(connection: Connection): GoalWorkflowStats = buildGoalStats(
    loadGoalRows(connection, "goal_run_sessions"),
    loadGoalRows(connection, "goal_subtask_events"),
  )

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
  ): ReviewFinishedTelemetry = reviewFinishedPayload(
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
  ): ReviewFinishedTelemetry? {
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
