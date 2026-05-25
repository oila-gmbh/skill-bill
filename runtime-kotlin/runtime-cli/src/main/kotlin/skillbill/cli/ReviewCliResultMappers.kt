package skillbill.cli

import skillbill.application.model.FeatureImplementStatsResult
import skillbill.application.model.FeatureVerifyStatsResult
import skillbill.application.model.ImportedReviewResult
import skillbill.application.model.ReviewFeedbackResult
import skillbill.application.model.ReviewPreviewResult
import skillbill.application.model.ReviewStatsResult
import skillbill.application.model.TriageResult
import skillbill.application.toFeatureImplementStatsPayload
import skillbill.application.toFeatureVerifyStatsPayload
import skillbill.application.toImportedReviewContract
import skillbill.application.toReviewFeedbackPayload
import skillbill.application.toReviewPreviewContract
import skillbill.application.toReviewStatsPayload
import skillbill.application.toTriagePayload

internal fun ReviewPreviewResult.toCliMap(): Map<String, Any?> = toReviewPreviewContract().toPayload()

internal fun ImportedReviewResult.toCliMap(): Map<String, Any?> = toImportedReviewContract().toPayload()

internal fun ReviewFeedbackResult.toCliMap(): Map<String, Any?> = toReviewFeedbackPayload().toPayload()

internal fun TriageResult.toCliMap(): Map<String, Any?> = toTriagePayload().toPayload()

internal fun ReviewStatsResult.toCliMap(): Map<String, Any?> = toReviewStatsPayload().toPayload()

internal fun FeatureImplementStatsResult.toCliMap(): Map<String, Any?> = toFeatureImplementStatsPayload().toPayload()

internal fun FeatureVerifyStatsResult.toCliMap(): Map<String, Any?> = toFeatureVerifyStatsPayload().toPayload()
