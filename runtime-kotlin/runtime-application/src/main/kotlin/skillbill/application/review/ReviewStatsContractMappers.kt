package skillbill.application.review

import skillbill.contracts.review.ReviewVerificationSignalKeys

import skillbill.application.review.model.FeatureTaskRuntimeStatsResult
import skillbill.application.review.model.FeatureVerifyStatsResult
import skillbill.application.review.model.GoalStatsResult
import skillbill.application.review.model.ReviewStatsResult
import skillbill.contracts.JsonPayloadContract
import skillbill.ports.workflow.model.toPayload

fun ReviewStatsResult.toReviewStatsPayload(): JsonPayloadContract = MapPayloadContract(
  LinkedHashMap(stats.toPayload()).apply {
    put("health", health.toPayload())
    put(ReviewVerificationSignalKeys.REVIEW_RUN_ID, reviewRunId)
    put("db_path", dbPath)
    stageMetrics?.let { putAll(it.toStageMetricsPayload()) }
    if (stageMetricsByTier.isNotEmpty()) {
      put(
        "stage_metrics_by_tier",
        stageMetricsByTier.mapValues { (_, metrics) -> metrics.toStageMetricsPayload() },
      )
    }
  },
)

fun FeatureVerifyStatsResult.toFeatureVerifyStatsPayload(): JsonPayloadContract =
  MapPayloadContract(LinkedHashMap(stats.toPayload()).apply { put("db_path", dbPath) })

fun FeatureTaskRuntimeStatsResult.toFeatureTaskRuntimeStatsPayload(): JsonPayloadContract =
  MapPayloadContract(LinkedHashMap(stats.toPayload()).apply { put("db_path", dbPath) })

fun GoalStatsResult.toGoalStatsPayload(): JsonPayloadContract =
  MapPayloadContract(LinkedHashMap(stats.toPayload()).apply { put("db_path", dbPath) })

private class MapPayloadContract(
  private val payload: Map<String, Any?>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = payload
}
