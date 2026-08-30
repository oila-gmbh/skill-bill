package skillbill.application.review

import skillbill.review.model.ReviewStageMetrics
import skillbill.review.model.ReviewStageVerdictDistribution

internal fun ReviewStageMetrics.toStageMetricsPayload(): Map<String, Any?> = linkedMapOf(
  "verification" to verification.toStageMetricsPayload(),
  "adjudication" to adjudication.toStageMetricsPayload(),
  "refutation_rate_by_stage" to linkedMapOf(
    "verification" to verificationRefutationRate,
    "adjudication" to adjudicationRefutationRate,
  ),
  "rejected_verdict_counts" to linkedMapOf(
    "uncited_refutations" to rejectedVerdictCounts.uncitedRefutations,
    "uncited_downgrades" to rejectedVerdictCounts.uncitedDowngrades,
    "finding_mutations" to rejectedVerdictCounts.findingMutations,
  ),
  "severity_adjustment_counts" to linkedMapOf(
    "raised" to severityAdjustmentCounts.raised,
    "lowered" to severityAdjustmentCounts.lowered,
  ),
  "resolved_tier" to resolvedTier,
)

internal fun ReviewStageVerdictDistribution.toStageMetricsPayload(): Map<String, Any?> = linkedMapOf(
  "claim_verdict" to linkedMapOf(
    "confirmed" to confirmed,
    "refuted" to refuted,
    "unresolved" to unresolved,
  ),
  "scope_disposition" to linkedMapOf(
    "in_scope" to inScope,
    "out_of_scope_preexisting" to outOfScopePreexisting,
    "spec_deviation" to specDeviation,
    "spec_accepted_tradeoff" to specAcceptedTradeoff,
  ),
  "finding_count" to findingCount,
)
