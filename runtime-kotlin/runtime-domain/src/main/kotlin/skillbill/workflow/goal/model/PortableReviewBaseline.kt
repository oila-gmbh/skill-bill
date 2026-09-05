package skillbill.workflow.goal.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION

const val GOAL_RECOVERY_AUDIT_ARTIFACT_KEY: String = "goal_recovery_audit"
const val PORTABLE_REVIEW_BASELINE_DIRECTORY: String = "portable-review-baselines"

enum class PortableReviewBaselineBlockedReason(val wireValue: String, val recoveryAction: String) {
  ARTIFACT_MISSING(
    "portable_artifact_missing",
    "Capture a fresh baseline with `skill-bill goal repair --replace-orphan --apply` when the child " +
      "has no durable execution evidence, or rerun the child on the original machine.",
  ),
  ARTIFACT_MALFORMED(
    "portable_artifact_malformed",
    "Repair or replace the portable baseline artifact; do not infer baseline paths from the worktree.",
  ),
  DIGEST_MISMATCH(
    "portable_artifact_digest_mismatch",
    "Replace the tampered portable baseline through `skill-bill goal repair --replace-orphan --apply` " +
      "when safe, or restore the original artifact from version control.",
  ),
  REPOSITORY_MISMATCH(
    "portable_artifact_repository_mismatch",
    "Open the repository that recorded the portable baseline, or replace the orphan child on this repository.",
  ),
  BRANCH_MISMATCH(
    "portable_artifact_branch_mismatch",
    "Checkout the goal child branch named in the artifact before resuming.",
  ),
  UNREACHABLE_BASE(
    "portable_artifact_unreachable_base",
    "Recover the unreachable review base with `skill-bill goal repair --apply`, or replace the orphan " +
      "child when no durable execution evidence exists.",
  ),
  UNSAFE_PATH(
    "portable_artifact_unsafe_path",
    "Remove unsafe untracked paths from the artifact inventory or replace the orphan child when safe.",
  ),
  IMPLEMENTATION_EVIDENCE(
    "portable_artifact_implementation_evidence",
    "Do not rehydrate review state after implementation evidence exists; resume on the original machine " +
      "or replace the orphan child only before durable execution.",
  ),
  BRANCH_EVIDENCE(
    "portable_artifact_branch_evidence",
    "Resolve conflicting branch evidence before rehydrating portable review state.",
  ),
  ;

  companion object {
    fun fromWire(value: String): PortableReviewBaselineBlockedReason? = entries.firstOrNull { it.wireValue == value }
  }
}

data class PortableReviewBaseline(
  val contractVersion: String = GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION,
  val workflowId: String,
  val repositoryIdentity: String,
  val goalBranch: String,
  val reviewBaseSha: String,
  val baselineUntrackedPaths: List<String>,
  val ownedPathspec: List<String> = emptyList(),
  val integrityDigest: String,
) {
  init {
    require(contractVersion == GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION) {
      "Portable review baseline contract version '$contractVersion' is unsupported."
    }
    require(workflowId.isNotBlank()) { "workflow_id is required." }
    require(repositoryIdentity.isNotBlank()) { "repository_identity is required." }
    require(goalBranch.isNotBlank()) { "goal_branch is required." }
    require(baselineUntrackedPaths.all(String::isNotBlank)) { "baseline_untracked_paths must not contain blanks." }
    require(ownedPathspec.all(String::isNotBlank)) { "owned_pathspec must not contain blanks." }
  }
}

data class GoalRecoveryAuditEntry(
  val sourceWorkflowId: String,
  val replacementWorkflowId: String?,
  val artifactDigest: String,
  val selectedBase: String,
  val recoveryReason: String,
) {
  @OpenBoundaryMap("Recovery audit record at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "source_workflow_id" to sourceWorkflowId,
    "replacement_workflow_id" to replacementWorkflowId,
    "artifact_digest" to artifactDigest,
    "selected_base" to selectedBase,
    "recovery_reason" to recoveryReason,
  )
}
