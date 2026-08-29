package skillbill.mcp.review

import skillbill.application.review.model.FeatureVerifyStatsResult
import skillbill.application.review.model.GoalStatsResult
import skillbill.application.review.model.ImportedReviewResult
import skillbill.application.review.model.ReviewStatsResult
import skillbill.application.review.model.TriageResult
import skillbill.application.review.toFeatureVerifyStatsPayload
import skillbill.application.review.toGoalStatsPayload
import skillbill.application.review.toImportedReviewContract
import skillbill.application.review.toReviewStatsPayload
import skillbill.application.review.toTriagePayload

internal fun ImportedReviewResult.toMcpMap(): Map<String, Any?> = toImportedReviewContract().toPayload()

internal fun TriageResult.toMcpMap(): Map<String, Any?> = toTriagePayload().toPayload()

internal fun ReviewStatsResult.toMcpMap(): Map<String, Any?> = toReviewStatsPayload().toPayload()

internal fun FeatureVerifyStatsResult.toMcpMap(): Map<String, Any?> = toFeatureVerifyStatsPayload().toPayload()

internal fun GoalStatsResult.toMcpMap(): Map<String, Any?> = toGoalStatsPayload().toPayload()
