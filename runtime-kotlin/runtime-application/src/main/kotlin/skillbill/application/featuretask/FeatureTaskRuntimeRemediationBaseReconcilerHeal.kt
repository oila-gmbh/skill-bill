package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.PersistHealedRemediationBaseRequest
import skillbill.application.featuretask.model.ResolvedReviewFixCheckpoint
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal fun remediationBaseHealReason(
  stored: String?,
  target: String,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
): String = when {
  stored == null -> "committed_but_unrecorded"
  latestRemediationResolved != null && latestRemediationResolved.sha == target -> "committed_but_unrecorded"
  else -> "recorded_but_superseded"
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.persistHealedRemediationBaseState(
  request: PersistHealedRemediationBaseRequest,
): GoalSubtaskReviewState? {
  val head = request.gitOperations.headCommitSha(request.repoRoot)
  if (!head.ok || head.value.isBlank()) throw remediationGitFailure("HEAD could not be read (${head.error})")
  val headSha = head.value.trim()
  return database.transaction(request.dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: throw remediationGitFailure(
        "workflow row '${request.workflowId}' could not be read while persisting remediation state",
      )
    val artifacts = decodeArtifacts(record.artifactsJson)
    val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
    if (latest.remediationBaseSha == request.target) return@transaction latest
    val updated = latest.copy(remediationBaseSha = request.target)
    val evidenceEntry = remediationBaseRecoveryEvidenceEntry(
      RemediationBaseRecovery(
        originalSha = request.stored,
        replacementSha = request.target,
        reason = request.reason,
        goalBranch = request.continuation.goalBranch,
        headSha = headSha,
      ),
    )
    val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to updated.toArtifactMap(),
        GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry,
      ),
    )
    updated
  }
}

internal fun recoveredRemediationBaseSha(
  stored: String?,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): String? {
  if (stored == null) return null
  val request = GoalSubtaskReviewBaselineRecoveryRequest(
    unreachableSha = stored,
    failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
  )
  val recovered = gitOperations.recoverGoalSubtaskReviewBaseline(repoRoot, request, continuation.goalBranch)
  if (!recovered.ok) throw remediationGitFailure("baseline recovery failed (${recovered.error})")
  return recovered.baseline?.reviewBaseSha
    ?: throw remediationGitFailure("baseline recovery returned no reachable review base")
}
