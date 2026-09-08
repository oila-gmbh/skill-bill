package skillbill.application.review

import skillbill.application.review.model.FeatureTaskRuntimeStatsResult
import skillbill.application.review.model.FeatureVerifyStatsResult
import skillbill.application.review.model.GoalStatsResult
import skillbill.application.review.model.ReviewStatsResult
import skillbill.application.workflow.toPayload
import skillbill.contracts.JsonPayloadContract

fun ReviewStatsResult.toReviewStatsPayload(): JsonPayloadContract = MapPayloadContract(
  LinkedHashMap(stats.toPayload()).apply {
    put("health", health.toPayload())
    put("review_run_id", reviewRunId)
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
