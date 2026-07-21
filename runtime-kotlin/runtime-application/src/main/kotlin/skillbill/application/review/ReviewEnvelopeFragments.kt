package skillbill.application.review

import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewEvidenceTarget
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLearningsReference
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.ReviewRuleReference

internal fun ReviewRevision.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "session_id" to sessionId,
  "run_revision" to runRevision,
)

internal fun ReviewChangedHunk.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "hunk_id" to hunkId,
  "path" to path.normalizePath(),
  "old_start" to oldStart,
  "old_count" to oldCount,
  "new_start" to newStart,
  "new_count" to newCount,
  "content" to content.normalizeLineEndings(),
)

internal fun ReviewLaneDecision.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "lane" to lane,
  "included" to included,
  "reason" to reason,
  "signals" to signals.sorted(),
)

internal fun ReviewRuleReference.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "rule_id" to ruleId,
  "source_path" to sourcePath.normalizePath(),
  "excerpt" to excerpt.normalizeLineEndings(),
  "digest" to digest,
)

internal fun ReviewLearningsReference.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "learning_id" to learningId,
  "source" to source,
  "digest" to digest,
)

internal fun ReviewBuildTestFact.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "kind" to kind,
  "command" to command,
  "outcome" to outcome,
)

internal fun ReviewEvidenceTarget.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "target_id" to targetId,
  "path" to path.normalizePath(),
  "hunk_ids" to hunkIds.sorted(),
)

internal fun ReviewExpansionRecord.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "expansion_id" to expansionId,
  "assignment_digest" to assignmentDigest,
  "requested_path" to requestedPath.normalizePath(),
  "reachability_reason" to reachabilityReason,
  "authorized" to authorized,
  "sequence" to sequence,
)

internal fun ReviewContextBudgetPolicy.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "max_parent_packet_bytes" to maxParentPacketBytes,
  "max_lane_launch_bytes" to maxLaneLaunchBytes,
  "max_lane_evidence_bytes" to maxLaneEvidenceBytes,
  "max_evidence_result_bytes" to maxEvidenceResultBytes,
  "max_lane_result_bytes" to maxLaneResultBytes,
  "max_assignment_expansions" to maxAssignmentExpansions,
  "provider_token_thresholds" to linkedMapOf(
    "input_tokens" to providerTokenThresholds.inputTokens,
    "cached_input_tokens" to providerTokenThresholds.cachedInputTokens,
    "output_tokens" to providerTokenThresholds.outputTokens,
    "reasoning_tokens" to providerTokenThresholds.reasoningTokens,
    "total_tokens" to providerTokenThresholds.totalTokens,
  ),
)
