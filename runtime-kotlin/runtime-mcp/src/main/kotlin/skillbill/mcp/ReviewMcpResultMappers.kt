package skillbill.mcp

import skillbill.application.model.FeatureImplementStatsResult
import skillbill.application.model.FeatureVerifyStatsResult
import skillbill.application.model.ImportedReviewResult
import skillbill.application.model.ReviewStatsResult
import skillbill.application.model.TriageResult
import skillbill.application.toFeatureImplementStatsPayload
import skillbill.application.toFeatureVerifyStatsPayload
import skillbill.application.toImportedReviewContract
import skillbill.application.toReviewStatsPayload
import skillbill.application.toTriagePayload

internal fun ImportedReviewResult.toMcpMap(): Map<String, Any?> = toImportedReviewContract().toPayload()

internal fun TriageResult.toMcpMap(): Map<String, Any?> = toTriagePayload().toPayload()

internal fun ReviewStatsResult.toMcpMap(): Map<String, Any?> = toReviewStatsPayload().toPayload()

internal fun FeatureImplementStatsResult.toMcpMap(): Map<String, Any?> = toFeatureImplementStatsPayload().toPayload()

internal fun FeatureVerifyStatsResult.toMcpMap(): Map<String, Any?> = toFeatureVerifyStatsPayload().toPayload()
