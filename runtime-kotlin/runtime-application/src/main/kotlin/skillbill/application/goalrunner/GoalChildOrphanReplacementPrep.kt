package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalChildOrphanReplacementRequest
import skillbill.application.goalrunner.model.GoalChildOrphanReplacementResult
import skillbill.ports.workflow.gitops.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.PortableReviewBaselineBlockedReason

internal sealed interface OrphanPrep {
  data class Identity(val sourceWorkflowId: String) : OrphanPrep
  data class Ready(
    val sourceWorkflowId: String,
    val branch: String,
    val reviewBaseline: GoalSubtaskReviewBaseline,
  ) : OrphanPrep

  data class Rejected(val result: GoalChildOrphanReplacementResult.Blocked) : OrphanPrep
}

internal object GoalChildOrphanReplacementPrep {
  fun prepare(request: GoalChildOrphanReplacementRequest): OrphanPrep {
    val identity = resolveOrphanIdentity(request)
    if (identity is OrphanPrep.Rejected) return identity
    val readyIdentity = identity as OrphanPrep.Identity
    val branch = replacementBranch(request)
      ?: return rejected(
        PortableReviewBaselineBlockedReason.BRANCH_MISMATCH,
        "Subtask '${request.subtaskId}' has no durable child branch for baseline capture.",
      )
    val baselineCapture = request.gitOperations.captureGoalSubtaskReviewBaseline(request.repoRoot, branch)
    if (!baselineCapture.ok) {
      return rejected(PortableReviewBaselineBlockedReason.UNREACHABLE_BASE, baselineCapture.error)
    }
    return OrphanPrep.Ready(
      sourceWorkflowId = readyIdentity.sourceWorkflowId,
      branch = branch,
      reviewBaseline = requireNotNull(baselineCapture.baseline),
    )
  }

  private fun resolveOrphanIdentity(request: GoalChildOrphanReplacementRequest): OrphanPrep {
    val subtask = request.state.manifest.subtasks.firstOrNull { it.id == request.subtaskId }
      ?: return rejected(
        PortableReviewBaselineBlockedReason.ARTIFACT_MALFORMED,
        "Subtask '${request.subtaskId}' is missing.",
      )
    val identityBlock = orphanIdentityBlock(request, subtask)
    return if (identityBlock != null) {
      OrphanPrep.Rejected(identityBlock)
    } else {
      OrphanPrep.Identity(requireNotNull(subtask.workflowId?.takeIf(String::isNotBlank)))
    }
  }

  private fun orphanIdentityBlock(
    request: GoalChildOrphanReplacementRequest,
    subtask: DecompositionSubtask,
  ): GoalChildOrphanReplacementResult.Blocked? {
    if (subtask.workflowId.isNullOrBlank()) {
      return blocked(
        PortableReviewBaselineBlockedReason.ARTIFACT_MISSING,
        "Subtask '${request.subtaskId}' has no workflow identity to retire.",
      )
    }
    if (!isOrphanCandidate(subtask)) {
      return blocked(
        PortableReviewBaselineBlockedReason.IMPLEMENTATION_EVIDENCE,
        "Subtask '${request.subtaskId}' has durable execution evidence; orphan replacement is not allowed.",
      )
    }
    return null
  }

  private fun replacementBranch(request: GoalChildOrphanReplacementRequest): String? =
    request.state.manifest.branchPlanFor(request.subtaskId).branch.takeIf(String::isNotBlank)
      ?: request.state.manifest.featureBranch?.takeIf(String::isNotBlank)

  private fun isOrphanCandidate(subtask: DecompositionSubtask): Boolean {
    if (!subtask.commitSha.isNullOrBlank()) return false
    return subtask.lastResumableStep == null || subtask.lastResumableStep == "create_branch"
  }

  private fun rejected(reason: PortableReviewBaselineBlockedReason, detail: String): OrphanPrep.Rejected =
    OrphanPrep.Rejected(blocked(reason, detail))

  private fun blocked(
    reason: PortableReviewBaselineBlockedReason,
    detail: String,
  ): GoalChildOrphanReplacementResult.Blocked = GoalChildOrphanReplacementResult.Blocked(reason, detail)
}
