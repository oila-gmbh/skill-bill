package skillbill.infrastructure.sqlite.review
import skillbill.db.PARAM_ONE
import skillbill.ports.review.model.ReviewRepositoryStatsSnapshot
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.ReviewFinishedTelemetry
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
      laneEffectiveness = queryReviewLaneEffectiveness(connection, reviewRunId),
      stageMetrics = reviewRunId?.let { runId ->
        aggregateReviewStageMetrics(
          connection,
          runId,
          queryLatestFindingOutcomes(connection, runId).size,
        )
      },
      stageMetricsByTier = if (reviewRunId == null) {
        stageMetricsByResolvedTier(connection)
      } else {
        emptyMap()
      },
    )
  }

  fun featureVerifyStats(connection: Connection): FeatureVerifyWorkflowStats =
    buildFeatureVerifyStats(loadRows(connection, "feature_verify_sessions"))

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

  fun buildReviewFinishedPayload(request: ReviewFinishedPayloadBuildRequest): ReviewFinishedTelemetry =
    reviewFinishedPayload(
      connection = request.connection,
      reviewSummary = request.reviewSummary
        ?: ReviewRuntime.fetchReviewSummary(request.connection, request.reviewRunId),
      findingRows = request.findingRows ?: queryLatestFindingOutcomes(request.connection, request.reviewRunId),
      level = request.level,
      routedSkillPlatformSlugs = request.routedSkillPlatformSlugs,
    )

  fun updateReviewFinishedTelemetryState(
    connection: Connection,
    reviewRunId: String,
    enabled: Boolean? = null,
    level: String? = null,
    routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
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
          routedSkillPlatformSlugs = routedSkillPlatformSlugs,
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
