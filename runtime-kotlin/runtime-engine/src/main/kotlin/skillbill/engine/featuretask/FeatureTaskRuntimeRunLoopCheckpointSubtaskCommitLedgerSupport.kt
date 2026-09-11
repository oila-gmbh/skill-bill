package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.ports.workflow.gitops.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import kotlin.coroutines.cancellation.CancellationException

internal fun persistCreatedSubtaskCommitRef(
  runLoop: FeatureTaskRuntimeRunLoop,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  sequenceNumber: Int,
  committed: WorkflowGitOperationResult,
): WorkflowGitOperationResult {
  val commitSha = committed.value.orEmpty().trim()
  val parentSha = resolveCreatedCommitParent(runLoop, commitSha)
    ?: return reconciliationFailureResult(
      runLoop,
      identity,
      "created subtask commit '$commitSha' has no resolvable parent for recovery-ref persistence",
      null,
    )
  return persistCreatedSubtaskCommitRefWithParent(runLoop, identity, sequenceNumber, committed, parentSha)
}

private fun persistCreatedSubtaskCommitRefWithParent(
  runLoop: FeatureTaskRuntimeRunLoop,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  sequenceNumber: Int,
  committed: WorkflowGitOperationResult,
  parentSha: String,
): WorkflowGitOperationResult {
  val refName = identity.checkpointRefName(sequenceNumber)
  val existing = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  if (!existing.ok) {
    return reconciliationFailureResult(
      runLoop,
      identity,
      "create-path recovery ref '$refName' could not be inspected (${existing.error})",
      null,
    )
  }
  val occupant = existing.value.orEmpty().trim()
  if (occupant.isNotBlank() && occupant != parentSha) {
    return reconciliationFailureResult(
      runLoop,
      identity,
      "create-path recovery ref '$refName' names '$occupant' instead of parent '$parentSha'",
      null,
    )
  }
  if (occupant.isBlank()) {
    val written = runLoop.phaseGates.gitOperations.updateCheckpointRef(
      runLoop.request.repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      refName,
      parentSha,
    )
    if (!written.ok) {
      return reconciliationFailureResult(
        runLoop,
        identity,
        "create-path recovery ref '$refName' could not be written (${written.error})",
        null,
      )
    }
  }
  return verifyCreatedSubtaskCommitRef(runLoop, identity, refName, parentSha, committed)
}

private fun resolveCreatedCommitParent(runLoop: FeatureTaskRuntimeRunLoop, commitSha: String): String? =
  runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, "$commitSha^")
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)

private fun verifyCreatedSubtaskCommitRef(
  runLoop: FeatureTaskRuntimeRunLoop,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  refName: String,
  parentSha: String,
  committed: WorkflowGitOperationResult,
): WorkflowGitOperationResult {
  val verified = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  return if (verified.ok && verified.value.orEmpty().trim() == parentSha) {
    committed
  } else {
    reconciliationFailureResult(
      runLoop,
      identity,
      "create-path recovery ref '$refName' did not verify as parent '$parentSha'",
      null,
    )
  }
}

internal fun reconcileBeforeReviewForLedger(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): Boolean {
  if (!isGoalContinuationRun(runLoop.request)) return true
  val identity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
  val identities = loadCheckpointIdentitiesForReconciliation(
    runLoop,
    precedingPhaseId,
    branch,
    blockedReason,
  ) ?: return false
  val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
  val headSha = head.value.orEmpty().trim()
  if (!head.ok || headSha.isBlank()) {
    return blockLedgerReconciliation(
      runLoop,
      BlockReconciliationRequest(
        precedingPhaseId,
        branch,
        blockedReason,
        "checked-out HEAD could not be resolved; operator decision: resolve the subtask branch before resuming",
      ),
    )
  }
  val relevant = identities.filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
    .sortedBy { it.sequenceNumber }
  return reconcileLedgerHead(
    LedgerHeadReconciliationRequest(
      runLoop = runLoop,
      identity = identity,
      identities = identities,
      relevant = relevant,
      headSha = headSha,
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      blockedReason = blockedReason,
    ),
  )
}

private data class LedgerHeadReconciliationRequest(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val identities: List<FeatureTaskRuntimeCheckpointIdentity>,
  val relevant: List<FeatureTaskRuntimeCheckpointIdentity>,
  val headSha: String,
  val precedingPhaseId: String,
  val branch: String,
  val blockedReason: (String, String) -> String,
)

private fun reconcileLedgerHead(request: LedgerHeadReconciliationRequest): Boolean {
  val runLoop = request.runLoop
  val identity = request.identity
  val identities = request.identities
  val relevant = request.relevant
  val headSha = request.headSha
  val precedingPhaseId = request.precedingPhaseId
  val branch = request.branch
  val blockedReason = request.blockedReason
  val headMessage = runLoop.phaseGates.gitOperations.headCommitMessage(runLoop.request.repoRoot)
  if (!headMessage.ok || !identity.matches(headMessage.value.orEmpty())) {
    runCatching {
      runLoop.diagnostics.warning(
        "record_kind=migration seam=FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger." +
          "reconcileLedgerHead value_used='$headSha' value_expected=matching '${identity.trailer}' trailer " +
          "cause=HEAD lacks matching trailer; defaulting owned tip to HEAD",
      )
    }
  }
  val foreign = identities.firstOrNull { it.commitSha == headSha && it.issueKey != identity.issueKey }
  if (foreign != null) {
    return blockLedgerReconciliation(
      runLoop,
      BlockReconciliationRequest(
        precedingPhaseId,
        branch,
        blockedReason,
        "HEAD '$headSha' is durably attributed to another subtask '${foreign.issueKey}/${foreign.subtaskId}'; " +
          "operator decision: resolve the foreign subtask ownership before reviewing",
      ),
    )
  }
  val current = relevant.lastOrNull { it.commitSha == headSha }
  return if (current != null) {
    runLoop.collaborators.checkpointContinued5.verifyCurrentCheckpointTree(
      runLoop,
      current,
      precedingPhaseId,
      branch,
      blockedReason,
    )
  } else {
    recoverMissingCheckpointIdentity(
      MissingCheckpointIdentityRequest(
        runLoop,
        identity,
        relevant,
        identities,
        headSha,
        precedingPhaseId,
        branch,
        blockedReason,
      ),
    )
  }
}

private fun loadCheckpointIdentitiesForReconciliation(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): List<FeatureTaskRuntimeCheckpointIdentity>? = try {
  runLoop.recorder.loadCheckpointIdentities(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    ?: throw FeatureTaskRuntimeSubtaskCommitReconciliationError(
      runLoop.request.workflowId,
      runLoop.request.issueKey,
      runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
      "the workflow row is missing its checkpoint-identity ledger; operator decision: restore the durable identity " +
        "before resuming",
    )
} catch (error: FeatureTaskRuntimeSubtaskCommitReconciliationError) {
  blockLedgerReconciliation(
    runLoop,
    BlockReconciliationRequest(
      precedingPhaseId,
      branch,
      blockedReason,
      error.message.orEmpty(),
      error,
    ),
  )
  null
} catch (error: IllegalStateException) {
  blockLedgerReconciliation(
    runLoop,
    BlockReconciliationRequest(
      precedingPhaseId,
      branch,
      blockedReason,
      "durable checkpoint identities could not be read (${error.message ?: error::class.simpleName}); " +
        "operator decision: " +
        "repair the workflow store before resuming",
      error,
    ),
  )
  null
}

internal data class MissingCheckpointIdentityRequest(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val relevant: List<FeatureTaskRuntimeCheckpointIdentity>,
  val identities: List<FeatureTaskRuntimeCheckpointIdentity>,
  val headSha: String,
  val precedingPhaseId: String,
  val branch: String,
  val blockedReason: (String, String) -> String,
)

private fun recoverMissingCheckpointIdentity(request: MissingCheckpointIdentityRequest): Boolean {
  val runLoop = request.runLoop
  val headSha = request.headSha
  val tree = runLoop.phaseGates.gitOperations.resolveTree(runLoop.request.repoRoot, headSha)
  if (!tree.ok || tree.value.orEmpty().isBlank()) {
    return blockLedgerReconciliation(
      runLoop,
      BlockReconciliationRequest(
        request.precedingPhaseId,
        request.branch,
        request.blockedReason,
        "the tree for committed HEAD '$headSha' could not be resolved; operator decision: repair Git object access " +
          "before reviewing",
      ),
    )
  }
  val recovered = resolveRecoveredCheckpointParent(request)
  recovered.parentSha?.let { return recordRecoveredCheckpointIdentity(request, it) }
  return blockLedgerReconciliation(
    runLoop,
    BlockReconciliationRequest(
      request.precedingPhaseId,
      request.branch,
      request.blockedReason,
      "${recovered.failure}; operator decision: reconcile the durable checkpoint before reviewing",
    ),
  )
}

private fun recordRecoveredCheckpointIdentity(request: MissingCheckpointIdentityRequest, parentSha: String): Boolean {
  val resolved = runCatching {
    request.runLoop.recorder.loadResolvedBranch(
      request.runLoop.request.workflowId,
      request.runLoop.request.dbPathOverride,
    )
  }.getOrElse { error ->
    if (error is CancellationException) throw error
    return blockLedgerReconciliation(
      request.runLoop,
      BlockReconciliationRequest(
        request.precedingPhaseId,
        request.branch,
        request.blockedReason,
        "durable resolved-branch ownership could not be read (${error.message ?: error::class.simpleName}); " +
          "operator decision: repair the workflow store before reviewing",
        error,
      ),
    )
  }
  val recorded = request.runLoop.collaborators.checkpointContinued5.recordCheckpointIdentity(
    request.runLoop,
    RecordCheckpointIdentityArgs(
      precedingPhaseId = request.precedingPhaseId,
      branch = request.branch,
      loopId = null,
      ownedPaths = resolved?.workflowOwnedPaths.orEmpty()
        .filterNot(::isGovernedSpecPath)
        .filterNot(::isRuntimePrivatePath),
      parentSha = parentSha,
      commitSha = request.headSha,
      blockedReason = request.blockedReason,
    ),
  )
  if (!recorded) return false
  return persistRecoveredCheckpointParentRef(request, parentSha)
}

internal fun blockLedgerReconciliation(
  runLoop: FeatureTaskRuntimeRunLoop,
  request: BlockReconciliationRequest,
): Boolean {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
    reason = request.reason,
    cause = request.cause,
  )
  runCatching {
    runLoop.diagnostics.warning(
      "record_kind=refusal seam=FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger.reconcileBeforeReview " +
        "value_used='${request.branch}' value_expected=matching HEAD, trailer, checkpoint ref, tree, and durable " +
        "identity cause=${request.reason}",
      error,
    )
  }
  return runLoop.collaborators.checkpointContinued6.blockCheckpoint(
    runLoop,
    request.precedingPhaseId,
    request.branch,
    error.message.orEmpty(),
    request.blockedReason,
  )
}
