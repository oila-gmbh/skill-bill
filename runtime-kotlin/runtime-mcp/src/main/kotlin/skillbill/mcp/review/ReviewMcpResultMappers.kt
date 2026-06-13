package skillbill.mcp.review

import skillbill.application.model.FeatureImplementStatsResult
import skillbill.application.model.FeatureTaskRuntimeStatsResult
import skillbill.application.model.FeatureVerifyStatsResult
import skillbill.application.model.GoalStatsResult
import skillbill.application.model.ImportedReviewResult
import skillbill.application.model.ReviewStatsResult
import skillbill.application.model.TriageResult
import skillbill.application.review.toFeatureImplementStatsPayload
import skillbill.application.review.toFeatureTaskRuntimeStatsPayload
import skillbill.application.review.toFeatureVerifyStatsPayload
import skillbill.application.review.toGoalStatsPayload
import skillbill.application.review.toImportedReviewContract
import skillbill.application.review.toReviewStatsPayload
import skillbill.application.review.toTriagePayload

internal fun ImportedReviewResult.toMcpMap(): Map<String, Any?> = toImportedReviewContract().toPayload()

internal fun TriageResult.toMcpMap(): Map<String, Any?> = toTriagePayload().toPayload()

internal fun ReviewStatsResult.toMcpMap(): Map<String, Any?> = toReviewStatsPayload().toPayload()

internal fun FeatureImplementStatsResult.toMcpMap(): Map<String, Any?> = toFeatureImplementStatsPayload().toPayload()

internal fun FeatureVerifyStatsResult.toMcpMap(): Map<String, Any?> = toFeatureVerifyStatsPayload().toPayload()

internal fun FeatureTaskRuntimeStatsResult.toMcpMap(): Map<String, Any?> =
  toFeatureTaskRuntimeStatsPayload().toPayload()

internal fun GoalStatsResult.toMcpMap(): Map<String, Any?> = toGoalStatsPayload().toPayload()
