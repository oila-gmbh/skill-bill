package skillbill.application.review

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingNode
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewCommitRoutingAccounting
import skillbill.review.context.model.ReviewIntegrationAccounting
import skillbill.review.context.model.ReviewLaneSegmentAccounting
import skillbill.review.context.model.ReviewParentAnalysisConsumption

/**
 * The sole durable/wire projection for review accounting. Content-bearing inputs are intentionally
 * absent.
 */
@OpenBoundaryMap("Schema-bounded review-accounting wire projection")
fun ReviewAccountingSummary.toBoundedPayload(): Map<String, Any?> = linkedMapOf(
  "contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION,
  "kind" to "accounting_summary",
  "review_id" to reviewId,
  "packet_digest" to packetDigest,
  "parent" to parent.toPayload(),
  "lanes" to lanes.map(ReviewAccountingNode::toPayload),
  "commit_routing_accounting" to commitRouting?.toPayload(),
  "parent_analysis_consumption" to parentAnalysis?.toPayload(),
  "integration" to integration?.toPayload(),
  "aggregate_counters" to aggregateCounters.toPayload(),
)

private fun ReviewAccountingNode.toPayload(): Map<String, Any?> = linkedMapOf(
  "lane" to lane,
  "assignment_digest" to assignmentDigest,
  "launch_bytes" to counters.launchBytes,
  "evidence_bytes" to counters.evidenceBytes,
  "result_bytes" to counters.resultBytes,
  "expansions" to counters.expansions.toLong(),
  "tool_calls" to counters.toolCalls.toLong(),
  "model_turns" to counters.modelTurns.toLong(),
  "inclusive_counters" to inclusiveCounters.toPayload(),
  "terminal_outcome" to terminalOutcome,
).apply {
  // Bundle keys are present-or-absent, never null: a lane with no bundle stays byte-identical.
  bundleCompositionDigest?.let { put("bundle_composition_digest", it) }
  segmentAccounting.takeIf { it.isNotEmpty() }
    ?.let { segments -> put("segment_accounting", segments.map { it.toPayload() }) }
  unreviewedSegmentIds.takeIf { it.isNotEmpty() }?.let { put("unreviewed_segment_ids", it) }
}

// Identity, counts, and lane names only. No commit subject, no path, no diff text: a routing shape
// is safe to persist, the code it routed is not.
private fun ReviewCommitRoutingAccounting.toPayload(): Map<String, Any?> = linkedMapOf(
  "commit_sequence_digest" to commitSequenceDigest,
  "routing_digest" to routingDigest,
  "commit_count" to commitCount,
  "lane_count" to laneCount,
  "focused_commit_count" to focusedCommitCount,
  "skipped_commit_count" to skippedCommitCount,
  "focused_pair_count" to focusedPairCount,
  "skipped_pair_count" to skippedPairCount,
  "incomplete_lanes" to incompleteLanes,
)

private fun ReviewParentAnalysisConsumption.toPayload(): Map<String, Any?> = linkedMapOf(
  "analyzed_pairs" to analyzedPairs,
  "analyzed_bytes" to analyzedBytes,
  "max_analysis_pairs" to maxAnalysisPairs,
  "max_analysis_bytes" to maxAnalysisBytes,
)

private fun ReviewIntegrationAccounting.toPayload(): Map<String, Any?> = linkedMapOf(
  "commit_sequence_digest" to commitSequenceDigest,
  "terminal_outcome" to terminalOutcome,
  "summarized_lane_count" to summarizedLaneCount,
  "finding_count" to findingCount,
  "counters" to counters.toPayload(),
).apply {
  skipReason?.let { put("skip_reason", it) }
}

private fun ReviewLaneSegmentAccounting.toPayload(): Map<String, Any?> = linkedMapOf(
  "segment_id" to segmentId,
  "measured_bytes" to measuredBytes,
  "entry_count" to entryCount,
  "composition_digest" to compositionDigest,
)

private fun ReviewAccountingCounters.toPayload(): Map<String, Long> = linkedMapOf(
  "launch_bytes" to launchBytes,
  "evidence_bytes" to evidenceBytes,
  "result_bytes" to resultBytes,
  "expansions" to expansions.toLong(),
  "tool_calls" to toolCalls.toLong(),
  "model_turns" to modelTurns.toLong(),
)
