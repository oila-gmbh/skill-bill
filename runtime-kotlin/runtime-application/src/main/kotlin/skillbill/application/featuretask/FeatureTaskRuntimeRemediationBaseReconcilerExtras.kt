package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.PersistHealedRemediationBaseRequest
import skillbill.application.featuretask.model.RemediationBaseHealRequest
import skillbill.application.featuretask.model.RemediationReconciliationApplyRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal data class RemediationReconcileSnapshot(
  val state: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
)

internal sealed interface RemediationReconciliationDecision

internal data object RemediationReconciliationCoherent : RemediationReconciliationDecision

internal data object RemediationReconciliationBlocked : RemediationReconciliationDecision

internal data class RemediationReconciliationHeal(val sha: String) : RemediationReconciliationDecision

internal data class ResolvedReviewFixCheckpoint(
  val identity: FeatureTaskRuntimeCheckpointIdentity,
  val sha: String,
)

internal fun FeatureTaskRuntimeRemediationBaseReconciler.reconcileFromSnapshot(
  snapshot: RemediationReconcileSnapshot,
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  dbOverride: String?,
): RemediationBaseCoherenceResult {
  if (snapshot.state.remediationBaseSha == null &&
    snapshot.checkpoints.none { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
  ) {
    return RemediationBaseCoherent(snapshot.state)
  }
  val latestRemediationResolved = latestResolvedReviewFixCheckpointCommit(
    checkpoints = snapshot.checkpoints,
    gitOperations = gitOperations,
    repoRoot = repoRoot,
  )
  val reconciliation = decideRemediationReconciliation(
    snapshot = snapshot,
    latestRemediationResolved = latestRemediationResolved,
    gitOperations = gitOperations,
    repoRoot = repoRoot,
  )
  return applyRemediationReconciliation(
    RemediationReconciliationApplyRequest(
      reconciliation = reconciliation,
      snapshot = snapshot,
      workflowId = workflowId,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
      dbOverride = dbOverride,
      latestRemediationResolved = latestRemediationResolved,
    ),
  )
}

internal fun decideRemediationReconciliation(
  snapshot: RemediationReconcileSnapshot,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): RemediationReconciliationDecision {
  val stored = snapshot.state.remediationBaseSha
  val storedResolves = stored?.let { resolvesCommit(gitOperations, repoRoot, it) } == true
  return when {
    latestRemediationResolved != null &&
      (stored == null || isStrictAncestor(gitOperations, repoRoot, stored, latestRemediationResolved.sha)) ->
      RemediationReconciliationHeal(latestRemediationResolved.sha)
    stored != null && storedResolves ->
      storedBranchReconciliation(gitOperations, repoRoot, stored, latestRemediationResolved)
    latestRemediationResolved != null -> RemediationReconciliationHeal(latestRemediationResolved.sha)
    stored != null && !storedResolves -> RemediationReconciliationBlocked
    else -> RemediationReconciliationBlocked
  }
}

private fun storedBranchReconciliation(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  stored: String,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
): RemediationReconciliationDecision {
  val head = gitOperations.headCommitSha(repoRoot)
  if (!head.ok || head.value.isBlank()) return RemediationReconciliationCoherent
  val headSha = head.value.trim()
  val onBranch = gitOperations.isCommitAncestor(repoRoot, stored, headSha)
  return when {
    !onBranch.ok -> RemediationReconciliationCoherent
    onBranch.value == "true" -> RemediationReconciliationCoherent
    latestRemediationResolved != null -> RemediationReconciliationHeal(latestRemediationResolved.sha)
    else -> RemediationReconciliationBlocked
  }
}

private fun isStrictAncestor(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  ancestor: String,
  descendant: String,
): Boolean {
  if (ancestor == descendant) return false
  val ancestry = gitOperations.isCommitAncestor(repoRoot, ancestor, descendant)
  return ancestry.ok && ancestry.value == "true"
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.applyRemediationReconciliation(
  request: RemediationReconciliationApplyRequest,
): RemediationBaseCoherenceResult {
  val state = request.snapshot.state
  val continuation = request.snapshot.continuation
  val checkpoints = request.snapshot.checkpoints
  val stored = state.remediationBaseSha
  val storedResolves = stored?.let { resolvesCommit(request.gitOperations, request.repoRoot, it) } == true
  return when (request.reconciliation) {
    RemediationReconciliationCoherent -> RemediationBaseCoherent(state)
    RemediationReconciliationBlocked -> {
      val failedRef = latestReviewFixCheckpointRef(checkpoints)
      val guidance = remediationBaseReconciliationBlockedGuidance(
        workflowId = request.workflowId,
        continuation = continuation,
        failedRef = failedRef,
        storedSha = stored,
      )
      appendRemediationBaseReconciliationEvidence(
        workflowId = request.workflowId,
        recovery = RemediationBaseRecovery(
          originalSha = stored,
          replacementSha = null,
          reason = "reconciliation_blocked",
          goalBranch = continuation.goalBranch,
          failureMessageOverride = guidance,
        ),
        signal = RemediationDegradationSignal(
          seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
          valueUsed = failedRef ?: stored.orEmpty(),
          valueExpected = "resolvable review_fix checkpoint ref commit",
          cause = remediationBlockedCause(stored, storedResolves, failedRef),
        ),
        dbOverride = request.dbOverride,
      )
      RemediationBaseBlocked(guidance)
    }
    is RemediationReconciliationHeal -> healRemediationBase(
      RemediationBaseHealRequest(
        target = request.reconciliation.sha,
        stored = stored,
        storedResolves = storedResolves,
        state = state,
        continuation = continuation,
        workflowId = request.workflowId,
        gitOperations = request.gitOperations,
        repoRoot = request.repoRoot,
        dbOverride = request.dbOverride,
        latestRemediationResolved = request.latestRemediationResolved,
      ),
    )
  }
}

private fun FeatureTaskRuntimeRemediationBaseReconciler.healRemediationBase(
  request: RemediationBaseHealRequest,
): RemediationBaseCoherenceResult {
  if (request.target == request.stored) return RemediationBaseCoherent(request.state)
  val reason = remediationBaseHealReason(request.stored, request.target, request.latestRemediationResolved)
  if (!request.storedResolves && request.stored != null) {
    appendRemediationBaseReconciliationEvidence(
      workflowId = request.workflowId,
      recovery = RemediationBaseRecovery(
        originalSha = request.stored,
        replacementSha = request.target,
        reason = reason,
        goalBranch = request.continuation.goalBranch,
        failureMessageOverride =
        "Resume reconciled remediation_base_sha ($reason) through checkpoint ref after stored base missed.",
      ),
      signal = RemediationDegradationSignal(
        seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
        valueUsed = request.stored,
        valueExpected = "resolvable remediation_base_sha commit",
        cause = "stored remediation base did not resolve; reconciled through checkpoint ref",
      ),
      dbOverride = request.dbOverride,
    )
  }
  val healed = persistHealedRemediationBaseState(
    PersistHealedRemediationBaseRequest(
      workflowId = request.workflowId,
      target = request.target,
      stored = request.stored,
      reason = reason,
      continuation = request.continuation,
      gitOperations = request.gitOperations,
      repoRoot = request.repoRoot,
      dbOverride = request.dbOverride,
    ),
  )
  return RemediationBaseCoherent(healed ?: request.state)
}

internal fun latestResolvedReviewFixCheckpointCommit(
  checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): ResolvedReviewFixCheckpoint? = checkpoints
  .asReversed()
  .firstNotNullOfOrNull { identity ->
    if (identity.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
      return@firstNotNullOfOrNull null
    }
    resolveCheckpointRefCommit(gitOperations, repoRoot, identity.checkpointRef)
      ?.let { ResolvedReviewFixCheckpoint(identity, it) }
  }

internal fun resolvesCommit(gitOperations: WorkflowGitOperations, repoRoot: Path, sha: String): Boolean {
  val resolved = gitOperations.resolveCommit(repoRoot, sha.trim())
  return resolved.ok && resolved.value.orEmpty().trim().isNotBlank()
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.remediationBaseReconciliationBlockedGuidance(
  workflowId: String,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  failedRef: String?,
  storedSha: String?,
): String {
  val refDetail = failedRef?.let { "checkpoint ref '$it'" } ?: "stored remediation base"
  val storedDetail = storedSha?.let { " (stored sha '$it' also failed to resolve)" }.orEmpty()
  return "Remediation base reconciliation blocked for workflow '$workflowId' on branch " +
    "'${continuation.goalBranch}': $refDetail could not be resolved to a commit$storedDetail. " +
    "Run `skill-bill goal repair --issue-key ${continuation.issueKey} --subtask ${continuation.subtaskId} " +
    "--apply` to repoint or clear the unreachable remediation base, then resume the goal child."
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.appendRemediationBaseReconciliationEvidence(
  workflowId: String,
  recovery: RemediationBaseRecovery,
  signal: RemediationDegradationSignal,
  dbOverride: String?,
) {
  database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction
    val artifacts = decodeArtifacts(record.artifactsJson)
    val evidenceEntry = remediationBaseRecoveryEvidenceEntry(recovery, signal)
    val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry),
    )
  }
}
