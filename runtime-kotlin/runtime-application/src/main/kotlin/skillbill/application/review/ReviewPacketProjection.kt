package skillbill.application.review

import skillbill.application.review.model.ReviewContextEnvelope
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewPacketConsumerContract

fun ReviewContextPacket.toParentPacketEnvelope(): ReviewContextEnvelope = ReviewContextEnvelope(
  linkedMapOf(
    "contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION,
    "kind" to "parent_packet",
    "review_id" to reviewId,
    "packet_digest" to digest,
    "review_revision" to reviewRevision.toEnvelope(),
    "repository_identity" to repositoryIdentity,
    "base_revision" to baseRevision,
    "head_revision" to headRevision,
    "status" to status.normalizeLineEndings(),
    "stack" to stack,
    "pack" to pack,
    "add_ons" to addOns.sorted(),
    "composed_layers" to composedLayers,
    "selected_lanes" to selectedLanes,
    "lane_decisions" to laneDecisions
      .sortedWith(compareBy(ReviewLaneDecision::orderIndex, ReviewLaneDecision::lane))
      .map { it.toEnvelope() },
    "changed_hunks" to changedHunks
      .sortedWith(compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart))
      .map { it.toEnvelope() },
    "matched_rules" to matchedRules.sortedBy { it.ruleId }.map { it.toEnvelope() },
    "learnings_references" to learningsReferences.sortedBy { it.learningId }.map { it.toEnvelope() },
    "build_test_facts" to buildTestFacts.sortedWith(compareBy({ it.kind }, { it.command })).map { it.toEnvelope() },
    "dependency_allowlist" to dependencyAllowlist.normalized.sorted(),
    "baseline_untracked_policy" to baselineUntrackedPolicy.toEnvelope(),
    "evidence_targets" to evidenceTargets.sortedBy { it.targetId }.map { it.toEnvelope() },
    "expansion_ledger" to expansionLedger.sortedWith(compareBy({ it.sequence }, { it.expansionId }))
      .map { it.toEnvelope() },
  ),
)
fun ReviewAssignment.toAssignmentEnvelope(): ReviewContextEnvelope = ReviewContextEnvelope(
  linkedMapOf(
    "contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION,
    "kind" to "assignment",
    "review_id" to reviewId,
    "packet_digest" to packetDigest,
    "assignment_digest" to digest,
    "review_revision" to reviewRevision.toEnvelope(),
    "lane" to lane,
    "lane_decision" to laneDecision.toEnvelope(),
    "base_revision" to baseRevision,
    "head_revision" to headRevision,
    "assigned_paths" to assignedPaths.sorted(),
    "assigned_hunks" to assignedHunks.sorted(),
    "criteria_references" to criteriaReferences.sorted(),
    "matched_rules" to matchedRules.sortedBy { it.ruleId }.map { it.toEnvelope() },
    "evidence_targets" to evidenceTargets.sortedBy { it.targetId }.map { it.toEnvelope() },
    "dependency_allowlist" to dependencyAllowlist.normalized.sorted(),
    "baseline_untracked_policy" to baselineUntrackedPolicy.toEnvelope(),
    "expansions" to expansions.sortedWith(compareBy({ it.sequence }, { it.expansionId })).map { it.toEnvelope() },
  ),
)
fun GovernedReviewLaunch.toLaunchEnvelope(
  brokeredEvidence: List<Pair<String, String>> = emptyList(),
): ReviewContextEnvelope = ReviewContextEnvelope(
  linkedMapOf(
    "contract_version" to REVIEW_CONTEXT_CONTRACT_VERSION,
    "kind" to "launch",
    "review_id" to assignment.reviewId,
    "packet_digest" to assignment.packetDigest,
    "assignment_digest" to assignment.digest,
    "review_revision" to assignment.reviewRevision.toEnvelope(),
    "lane" to assignment.lane,
    "base_revision" to assignment.baseRevision,
    "head_revision" to assignment.headRevision,
    "specialist_contract" to specialistContract,
    "consumer_contract" to ReviewPacketConsumerContract.CONSUMER_CONTRACT,
    "rubric" to rubric,
    "assigned_paths" to assignment.assignedPaths.sorted(),
    "assigned_hunks" to assignment.assignedHunks.sorted(),
    "assigned_hunk_bodies" to packet.changedHunks
      .filter { it.hunkId in assignment.assignedHunks }
      .sortedWith(compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart))
      .map { it.toEnvelope() },
    "brokered_evidence" to brokeredEvidence.map { (path, content) ->
      linkedMapOf("path" to path, "content" to content)
    },
    "criteria_references" to assignment.criteriaReferences.sorted(),
    "matched_rules" to assignment.matchedRules.sortedBy { it.ruleId }.map { it.toEnvelope() },
    "evidence_targets" to assignment.evidenceTargets.sortedBy { it.targetId }.map { it.toEnvelope() },
    "dependency_allowlist" to assignment.dependencyAllowlist.normalized.sorted(),
    "baseline_untracked_policy" to assignment.baselineUntrackedPolicy.toEnvelope(),
    "forbidden_rediscovery" to ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY,
    "evidence_surface_rules" to ReviewPacketConsumerContract.EVIDENCE_SURFACE_RULES,
    "report_structure" to ReviewPacketConsumerContract.REPORT_STRUCTURE,
    "broker_id" to brokerId,
    "isolation" to isolation.name.lowercase(),
    "budget" to budget.toEnvelope(),
  ),
)
internal fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

private fun skillbill.review.context.model.ReviewBaselineUntrackedPolicy.toEnvelope() = linkedMapOf(
  "included_paths" to includedPaths.sorted(),
  "excluded_paths" to excludedPaths.sorted(),
)
