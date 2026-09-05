package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalChildOrphanReplacementRequest
import skillbill.application.goalrunner.model.GoalChildOrphanReplacementResult
import skillbill.application.workflow.generateWorkflowId
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.workflow.goal.model.GOAL_RECOVERY_AUDIT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalRecoveryAuditEntry

object GoalChildOrphanReplacement {
  private const val RUNTIME_WORKFLOW_ID_PREFIX = "wftr"

  fun replaceOrphan(request: GoalChildOrphanReplacementRequest): GoalChildOrphanReplacementResult =
    when (val prepared = GoalChildOrphanReplacementPrep.prepare(request)) {
      is OrphanPrep.Rejected -> prepared.result
      is OrphanPrep.Ready -> buildReplaced(request, prepared)
      is OrphanPrep.Identity -> error("orphan identity must be resolved before replacement")
    }

  fun childWorkflowSetup(
    request: GoalChildOrphanReplacementRequest,
    replacement: GoalChildOrphanReplacementResult.Replaced,
    governedSpecPath: String,
    reviewPolicy: GoalRunnerReviewPolicy,
  ): GoalRunnerChildWorkflowSetup = GoalRunnerChildWorkflowSetup(
    subtaskId = request.subtaskId,
    workflowId = replacement.replacementWorkflowId,
    goalBranch = request.state.manifest.branchPlanFor(request.subtaskId).branch.takeIf(String::isNotBlank)
      ?: request.state.manifest.featureBranch.orEmpty(),
    normalizedIssueKey = request.state.manifest.issueKey.trim().uppercase(),
    repositoryIdentity = request.repositoryIdentity,
    governedSpecPath = governedSpecPath,
    reviewBaseline = replacement.reviewBaseline,
    reviewPolicy = reviewPolicy,
    planningHydration = null,
  )

  fun auditArtifactKey(): String = GOAL_RECOVERY_AUDIT_ARTIFACT_KEY

  private fun buildReplaced(
    request: GoalChildOrphanReplacementRequest,
    prepared: OrphanPrep.Ready,
  ): GoalChildOrphanReplacementResult.Replaced {
    val replacementWorkflowId = generateWorkflowId(RUNTIME_WORKFLOW_ID_PREFIX)
    val artifactDigest = PortableReviewBaselineMapping.fromReviewBaseline(
      workflowId = replacementWorkflowId,
      repositoryIdentity = request.repositoryIdentity,
      goalBranch = prepared.branch,
      reviewBaseline = prepared.reviewBaseline,
    ).integrityDigest
    val updatedManifest = request.state.manifest.copy(
      subtasks = request.state.manifest.subtasks.map { entry ->
        if (entry.id == request.subtaskId) {
          entry.copy(
            workflowId = replacementWorkflowId,
            blockedReason = null,
            lastResumableStep = "create_branch",
            status = if (entry.status == "blocked") "in_progress" else entry.status,
          )
        } else {
          entry
        }
      },
    )
    return GoalChildOrphanReplacementResult.Replaced(
      state = request.state.copy(manifest = updatedManifest),
      sourceWorkflowId = prepared.sourceWorkflowId,
      replacementWorkflowId = replacementWorkflowId,
      reviewBaseline = prepared.reviewBaseline,
      auditEntry = GoalRecoveryAuditEntry(
        sourceWorkflowId = prepared.sourceWorkflowId,
        replacementWorkflowId = replacementWorkflowId,
        artifactDigest = artifactDigest,
        selectedBase = prepared.reviewBaseline.reviewBaseSha,
        recoveryReason = "orphan_replacement",
      ),
    )
  }
}
