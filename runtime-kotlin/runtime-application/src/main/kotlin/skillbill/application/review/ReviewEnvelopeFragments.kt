package skillbill.application.review

import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewEvidenceTarget
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneBundle
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
  "path" to path,
  "old_start" to oldStart,
  "old_count" to oldCount,
  "new_start" to newStart,
  "new_count" to newCount,
  "content" to content.normalizeLineEndings(),
)

internal fun ReviewCommitUnit.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "commit_unit_id" to commitUnitId,
  "commit_sha" to commitSha,
  "parent_sha" to parentSha,
  "subject" to subject.normalizeLineEndings(),
  "order_index" to orderIndex,
  "source" to source.name.lowercase(),
  "hunk_ids" to hunkIds,
)

/** Launch-side commit metadata: identity and order only, since the bodies below carry attribution. */
internal fun ReviewCommitUnit.toAssignedEnvelope(): Map<String, Any?> = linkedMapOf(
  "commit_unit_id" to commitUnitId,
  "commit_sha" to commitSha,
  "parent_sha" to parentSha,
  "subject" to subject.normalizeLineEndings(),
  "order_index" to orderIndex,
  "source" to source.name.lowercase(),
)

internal fun ReviewCommitCoverageFact.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "base_revision" to baseRevision,
  "head_revision" to headRevision,
  "commit_count" to commitCount,
  "chain_verified" to chainVerified,
  "path_coverage_verified" to pathCoverageVerified,
  "degraded_reason" to degradedReason,
)

internal fun ReviewLaneBundle.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "bundle_digest" to bundleDigest,
  "entries" to entries.map {
    linkedMapOf("commit_sha" to it.commitSha, "order_index" to it.orderIndex, "hunk_ids" to it.hunkIds)
  },
)

/** Commit attribution travels with the body, so a worker never needs Git to place a hunk. */
internal fun ReviewChangedHunk.toCommitAttributedEnvelope(commit: ReviewCommitUnit): Map<String, Any?> =
  linkedMapOf<String, Any?>("commit_sha" to commit.commitSha, "order_index" to commit.orderIndex) + toEnvelope()

internal fun ReviewLaneDecision.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "lane" to lane,
  "included" to included,
  "reason" to reason,
  "signals" to signals.sorted(),
  "owned_paths" to normalizedOwnedPaths.sorted(),
  "order_index" to orderIndex,
  "required_lane" to required,
  "origin_layer_chains" to originLayerChains,
  "owning_pack" to owningPack,
  "specialist_skill_name" to specialistSkillName,
  "add_ons" to addOns,
)

internal fun ReviewRuleReference.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "rule_id" to ruleId,
  "source_path" to sourcePath,
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
  "path" to path,
  "hunk_ids" to hunkIds.sorted(),
)

internal fun ReviewExpansionRecord.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "expansion_id" to expansionId,
  "assignment_digest" to assignmentDigest,
  "requested_path" to requestedPath,
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
  "max_specialist_tool_calls" to maxSpecialistToolCalls,
  "max_specialist_model_turns" to maxSpecialistModelTurns,
  "provider_token_thresholds" to linkedMapOf(
    "input_tokens" to providerTokenThresholds.inputTokens,
    "cached_input_tokens" to providerTokenThresholds.cachedInputTokens,
    "output_tokens" to providerTokenThresholds.outputTokens,
    "reasoning_tokens" to providerTokenThresholds.reasoningTokens,
    "total_tokens" to providerTokenThresholds.totalTokens,
  ),
)
