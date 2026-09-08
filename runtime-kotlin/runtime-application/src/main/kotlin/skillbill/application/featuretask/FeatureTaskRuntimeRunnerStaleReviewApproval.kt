package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private data class StaleReviewApprovalContext(
  val reviewedTarget: String,
  val reviewedTree: String,
  val currentHead: String,
  val currentTree: String,
  val boundaryHistory: BoundaryHistoryProjection,
  val dirtyRepair: Boolean,
  val durableTarget: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity?,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
)

internal fun invalidateStaleGoalReviewApprovalForGoalRuntime(
  runner: FeatureTaskRuntimeRunner,
  request: FeatureTaskRuntimeRunRequest,
) {
  val stateResult = runCatching {
    runner.goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
  }
  if (stateResult.isFailure) {
    staleApprovalFailure(
      runner,
      request,
      "goal-subtask review state could not be read (${stateResult.exceptionOrNull()?.message.orEmpty()})",
      stateResult.exceptionOrNull(),
    )
  }
  val state = stateResult.getOrNull() ?: return
  if (state.reviewedTargetSha == null || state.reviewedTreeSha == null) return
  val context = loadStaleReviewApprovalContext(runner, request, state.reviewedTargetSha, state.reviewedTreeSha)
  var identityReadFailure: String? = null
  if (!context.dirtyRepair && runner.phaseGates.gitOperations.reviewIdentityStillAuthoritative(
      request.repoRoot,
      context.reviewedTarget,
      context.currentHead,
      context.reviewedTree,
      context.currentTree,
      context.durableTarget,
      context.identity,
      onReadFailure = { identityReadFailure = it },
    )
  ) {
    return
  }
  if (identityReadFailure != null) {
    throw runner.staleApprovalReconciliationFailure(request, identityReadFailure.orEmpty(), null)
  }
  persistStaleReviewInvalidation(runner, request)
}

private fun loadStaleReviewApprovalContext(
  runner: FeatureTaskRuntimeRunner,
  request: FeatureTaskRuntimeRunRequest,
  reviewedTarget: String,
  reviewedTree: String,
): StaleReviewApprovalContext {
  val head = runner.phaseGates.gitOperations.headCommitSha(request.repoRoot)
  if (!head.ok || head.value.orEmpty().isBlank()) {
    staleApprovalFailure(runner, request, "current HEAD could not be resolved (${head.error})", null)
  }
  val currentHead = head.value.orEmpty().trim()
  val tree = runner.phaseGates.gitOperations.resolveCommit(request.repoRoot, "$currentHead^{tree}")
  if (!tree.ok || tree.value.orEmpty().isBlank()) {
    staleApprovalFailure(runner, request, "current HEAD tree could not be resolved (${tree.error})", null)
  }
  val resolvedBranch = runner.recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
  val phaseRecords = runner.recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
  val boundaryHistory = resolvedBranch?.boundaryHistoryProjection()
    ?.takeUnless { it.paths.isEmpty() && it.roots.isEmpty() }
    ?: declaredBoundaryHistoryProjection(
      phaseRecords?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY),
      resolvedBranch?.boundaryHistoryRoots.orEmpty(),
    )
  val dirty = runner.phaseGates.gitOperations.dirtyImplementationPaths(request.repoRoot)
  val dirtyRepair = when (dirty) {
    is DirtyPathsError -> staleApprovalFailure(runner, request, dirty.reason, null)
    is DirtyPaths -> dirty.paths.map(::normalizeRepoPath).any {
      !isGovernedSpecPath(it) && !isRuntimePrivatePath(it) &&
        !isBoundaryHistoryPath(it, boundaryHistory.paths, boundaryHistory.roots)
    }
  }
  val identity = FeatureTaskRuntimeSubtaskCommitIdentity(
    request.issueKey,
    request.goalContinuation?.subtaskId?.toString().orEmpty(),
  )
  val durableTarget = runner.recorder.loadCheckpointIdentities(request.workflowId, request.dbPathOverride)
    .orEmpty()
    .filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
    .maxByOrNull { it.sequenceNumber }
  return StaleReviewApprovalContext(
    reviewedTarget,
    reviewedTree,
    currentHead,
    tree.value.orEmpty().trim(),
    boundaryHistory,
    dirtyRepair,
    durableTarget,
    identity,
  )
}

private fun persistStaleReviewInvalidation(runner: FeatureTaskRuntimeRunner, request: FeatureTaskRuntimeRunRequest) {
  val persisted = runCatching {
    runner.recorder.persistReviewGenerationInvalidation(request.workflowId, request.dbPathOverride)
  }
  if (persisted.isFailure) {
    throw runner.staleApprovalReconciliationFailure(
      request,
      "stale goal-review approval invalidation could not be persisted " +
        "(${persisted.exceptionOrNull()?.message.orEmpty()})",
      persisted.exceptionOrNull(),
    )
  }
  if (persisted.getOrNull() == null) {
    throw runner.staleApprovalReconciliationFailure(
      request,
      "stale goal-review approval invalidation found no workflow row",
      null,
    )
  }
}

private fun staleApprovalFailure(
  runner: FeatureTaskRuntimeRunner,
  request: FeatureTaskRuntimeRunRequest,
  reason: String,
  cause: Throwable?,
): Nothing = throw runner.staleApprovalReconciliationFailure(request, reason, cause)
