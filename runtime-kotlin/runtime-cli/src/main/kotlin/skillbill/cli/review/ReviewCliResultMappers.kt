package skillbill.cli.review

import skillbill.application.model.FeatureImplementStatsResult
import skillbill.application.model.FeatureTaskRuntimeStatsResult
import skillbill.application.model.FeatureVerifyStatsResult
import skillbill.application.model.GoalStatsResult
import skillbill.application.model.ImportedReviewResult
import skillbill.application.model.ReviewFeedbackResult
import skillbill.application.model.ReviewPreviewResult
import skillbill.application.model.ReviewStatsResult
import skillbill.application.model.TriageResult
import skillbill.application.review.toFeatureImplementStatsPayload
import skillbill.application.review.toFeatureTaskRuntimeStatsPayload
import skillbill.application.review.toFeatureVerifyStatsPayload
import skillbill.application.review.toGoalStatsPayload
import skillbill.application.review.toImportedReviewContract
import skillbill.application.review.toReviewFeedbackPayload
import skillbill.application.review.toReviewPreviewContract
import skillbill.application.review.toReviewStatsPayload
import skillbill.application.review.toTriagePayload
import skillbill.cli.learning.toPayload

internal fun ReviewPreviewResult.toCliMap(): Map<String, Any?> = toReviewPreviewContract().toPayload()

internal fun ImportedReviewResult.toCliMap(): Map<String, Any?> = toImportedReviewContract().toPayload()

internal fun ReviewFeedbackResult.toCliMap(): Map<String, Any?> = toReviewFeedbackPayload().toPayload()

internal fun TriageResult.toCliMap(): Map<String, Any?> = toTriagePayload().toPayload()

internal fun ReviewStatsResult.toCliMap(): Map<String, Any?> = toReviewStatsPayload().toPayload()

internal fun FeatureImplementStatsResult.toCliMap(): Map<String, Any?> = toFeatureImplementStatsPayload().toPayload()

internal fun FeatureVerifyStatsResult.toCliMap(): Map<String, Any?> = toFeatureVerifyStatsPayload().toPayload()

internal fun FeatureTaskRuntimeStatsResult.toCliMap(): Map<String, Any?> =
  toFeatureTaskRuntimeStatsPayload().toPayload()

internal fun GoalStatsResult.toCliMap(): Map<String, Any?> = toGoalStatsPayload().toPayload()
