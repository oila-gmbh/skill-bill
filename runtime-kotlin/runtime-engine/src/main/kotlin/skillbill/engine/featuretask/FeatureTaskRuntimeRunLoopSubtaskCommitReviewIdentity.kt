package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

private data class ReviewIdentityContext(
  val target: String,
  val reviewedTree: String,
  val currentHead: String,
  val currentTree: String,
  val ledger: FeatureTaskRuntimeCheckpointIdentity?,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val dirtyPaths: List<String>,
  val ownedPaths: List<String>,
  val boundaryHistory: DeclaredBoundaryHistoryProjection,
)

private data class ReviewIdentityLoadResult(
  val context: ReviewIdentityContext? = null,
  val failure: String? = null,
  val cause: Throwable? = null,
)

internal fun reviewIdentityFailureForSubtask(runLoop: FeatureTaskRuntimeRunLoop): String? {
  return if (isGoalContinuationRun(runLoop.request)) {
    evaluateReviewIdentityForSubtask(runLoop)
  } else {
    null
  }
}

private fun evaluateReviewIdentityForSubtask(runLoop: FeatureTaskRuntimeRunLoop): String? {
  val loaded = loadReviewIdentityContext(runLoop)
  loaded.failure?.let { return reviewIdentityReconciliationFailure(runLoop, it, loaded.cause) }
  val context = loaded.context ?: return "goal-subtask review cannot prove its reviewed revision"
  val foreign = context.dirtyPaths.filterNot {
    isExemptFinalisationDirtyPath(it, context.ownedPaths, context.boundaryHistory)
  }
  return if (foreign.isEmpty()) {
    evaluateReviewIdentity(runLoop, context)
  } else {
    "needs_human: the durable subtask ownership inventory does not prove ownership of changed paths " +
      foreign.joinToString(", ")
  }
}

private class ReviewIdentityFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun loadReviewIdentityContext(runLoop: FeatureTaskRuntimeRunLoop): ReviewIdentityLoadResult = try {
  ReviewIdentityLoadResult(context = loadReviewIdentityContextData(runLoop))
} catch (error: ReviewIdentityFailure) {
  ReviewIdentityLoadResult(failure = error.message.orEmpty(), cause = error.cause)
} catch (error: IllegalStateException) {
  ReviewIdentityLoadResult(
    failure = "goal-subtask review identity could not be read (${error.message.orEmpty()})",
    cause = error,
  )
}

private fun loadReviewIdentityContextData(runLoop: FeatureTaskRuntimeRunLoop): ReviewIdentityContext {
  val refuse: (String) -> Nothing = { reason -> throw ReviewIdentityFailure(reason) }
  val state =
    runLoop.goalContinuationRecorder.reviewState(runLoop.request.workflowId, runLoop.request.dbPathOverride)
      ?: refuse("goal-subtask review state is missing; finalisation cannot prove the reviewed revision")
  val target = state.reviewedTargetSha
    ?: refuse("goal-subtask review has no recorded target SHA; finalisation cannot prove the reviewed revision")
  val reviewedTree = state.reviewedTreeSha
    ?: refuse("goal-subtask review has no recorded tree SHA; finalisation cannot prove the reviewed revision")
  val identity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
  val ledger = runLoop.recorder.loadCheckpointIdentities(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    ?.filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
    ?.maxByOrNull { it.sequenceNumber }
  val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
  if (!head.ok || head.value.orEmpty().isBlank()) {
    refuse("current HEAD could not be resolved while checking review identity (${head.error})")
  }
  val currentHead = head.value.trim()
  val currentTree = runLoop.phaseGates.gitOperations.resolveTree(runLoop.request.repoRoot, currentHead)
  if (!currentTree.ok || currentTree.value.orEmpty().isBlank()) {
    refuse("current HEAD tree could not be resolved while checking review identity (${currentTree.error})")
  }
  val dirty = runLoop.phaseGates.gitOperations.dirtyImplementationPaths(runLoop.request.repoRoot)
  if (dirty is DirtyPathsError) refuse(dirty.reason)
  val resolved = loadReviewIdentityBranch(runLoop)
    ?: refuse("durable subtask ownership could not be read")
  val phaseRecords = runLoop.recorder.loadPhaseRecords(runLoop.request.workflowId, runLoop.request.dbPathOverride)
  val boundaryHistory = resolved.boundaryHistoryProjection().takeUnless { it.paths.isEmpty() && it.roots.isEmpty() }
    ?: declaredBoundaryHistoryProjection(
      phaseRecords?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY),
      resolved.boundaryHistoryRoots,
    )
  return ReviewIdentityContext(
    target,
    reviewedTree,
    currentHead,
    currentTree.value.orEmpty().trim(),
    ledger,
    identity,
    (dirty as DirtyPaths).paths
      .map(::normalizeRepoPath)
      .filterNot(::isGovernedSpecPath)
      .filterNot(::isRuntimePrivatePath),
    finalisationOwnedPaths(resolved, phaseRecords),
    boundaryHistory,
  )
}

private fun loadReviewIdentityBranch(runLoop: FeatureTaskRuntimeRunLoop): FeatureTaskRuntimeResolvedBranch? =
  runLoop.recorder.loadResolvedBranch(runLoop.request.workflowId, runLoop.request.dbPathOverride)

private fun evaluateReviewIdentity(runLoop: FeatureTaskRuntimeRunLoop, context: ReviewIdentityContext): String? {
  if (ownedHeadMatchesTrailer(runLoop, context)) return null
  var readFailure: String? = null
  val authoritative = runLoop.phaseGates.gitOperations.reviewIdentityStillAuthoritative(
    ReviewIdentityAuthorityRequest(
      runLoop.request.repoRoot,
      context.target,
      context.currentHead,
      context.reviewedTree,
      context.currentTree,
      context.ledger,
      context.identity,
      onReadFailure = { readFailure = it },
    ),
  )
  if (authoritative) return null
  return readFailure?.let { reviewIdentityReconciliationFailure(runLoop, it, null) }
    ?: "needs_human: current HEAD '${context.currentHead}' is not the owned subtask commit"
}

private fun ownedHeadMatchesTrailer(runLoop: FeatureTaskRuntimeRunLoop, context: ReviewIdentityContext): Boolean {
  val headMessage = runLoop.phaseGates.gitOperations.headCommitMessage(runLoop.request.repoRoot)
  return headMessage.ok && context.identity.matches(headMessage.value.orEmpty())
}

private fun reviewIdentityReconciliationFailure(
  runLoop: FeatureTaskRuntimeRunLoop,
  reason: String,
  cause: Throwable?,
): String {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
    reason = reason,
    cause = cause,
  )
  runLoop.diagnostics.warning(
    "record_kind=refusal seam=FeatureTaskRuntimeRunLoopSubtaskCommit.reviewIdentityFailure " +
      "value_used='review identity' value_expected=durable current revision evidence cause=${error.reason}",
    error,
  )
  return "needs_human: ${error.message.orEmpty()}"
}
