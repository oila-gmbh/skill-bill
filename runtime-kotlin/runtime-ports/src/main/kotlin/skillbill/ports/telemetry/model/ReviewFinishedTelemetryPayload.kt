package skillbill.ports.telemetry.model

import skillbill.contracts.JsonPayloadContract
import skillbill.review.model.ReviewFindingDetail
import skillbill.review.model.ReviewFinishedFindingStats
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.ReviewLearningsSummary
import skillbill.review.model.ReviewStageMetrics
import skillbill.review.model.ReviewStageVerdictDistribution

fun ReviewFinishedTelemetry.toReviewFinishedTelemetryPayload(): JsonPayloadContract =
  ReviewFinishedTelemetryPayloadContract(this)

private class ReviewFinishedTelemetryPayloadContract(
  private val telemetry: ReviewFinishedTelemetry,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
    putAll(telemetry.findingStats.toPayload())
    put("review_run_id", telemetry.reviewRunId)
    put("review_session_id", telemetry.reviewSessionId)
    put("routed_skill", telemetry.routedSkill)
    put("review_subskills", telemetry.reviewSubskills)
    put("review_scope", telemetry.reviewScope)
    put("review_platform", telemetry.reviewPlatform)
    put("detected_stack", telemetry.detectedStack)
    telemetry.detectedStackDetail?.let { put("detected_stack_detail", it) }
    put("fallback", telemetry.fallback)
    telemetry.fallbackReason?.let { put("fallback_reason", it) }
    put("platform_slug", telemetry.platformSlug)
    put("scope_type", telemetry.scopeType)
    put("execution_mode", telemetry.executionMode)
    put("review_finished_at", telemetry.reviewFinishedAt)
    put("learnings", telemetry.learnings.toPayload())
    putAll(telemetry.stageMetrics.toStageMetricsPayload())
    telemetry.reviewContextAccounting?.let { put("review_context_accounting", it) }
  }
}

private fun ReviewFinishedFindingStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "total_findings" to totalFindings,
  "accepted_findings" to acceptedFindings,
  "rejected_findings" to rejectedFindings,
  "unresolved_findings" to unresolvedFindings,
  "accepted_rate" to acceptedRate,
  "rejected_rate" to rejectedRate,
  "accepted_finding_details" to acceptedFindingDetails.map(ReviewFindingDetail::toReviewFinishedPayload),
  "rejected_finding_details" to rejectedFindingDetails.map(ReviewFindingDetail::toReviewFinishedPayload),
)

private fun ReviewFindingDetail.toReviewFinishedPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "finding_id" to findingId,
  "issue_category" to issueCategory,
  "severity" to severity,
  "confidence" to confidence,
  "outcome_type" to outcomeType,
).apply {
  if (location.isNotEmpty()) put("location", location)
  if (description.isNotEmpty()) put("description", description)
  if (note.isNotEmpty()) put("note", note)
}

private fun ReviewLearningsSummary.toPayload(): Map<String, Any?> = linkedMapOf(
  "applied_count" to appliedCount,
  "applied_references" to appliedReferences,
  "applied_summary" to appliedSummary,
  "scope_counts" to scopeCounts,
  "entries" to entries.map { entry ->
    linkedMapOf<String, Any?>(
      "reference" to entry.reference,
      "scope" to entry.scope,
    ).apply {
      entry.title?.let { put("title", it) }
      entry.ruleText?.let { put("rule_text", it) }
    }.filterValues { it != null }
  },
)

private fun ReviewStageMetrics.toStageMetricsPayload(): Map<String, Any?> = linkedMapOf(
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

private fun ReviewStageVerdictDistribution.toStageMetricsPayload(): Map<String, Any?> = linkedMapOf(
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
