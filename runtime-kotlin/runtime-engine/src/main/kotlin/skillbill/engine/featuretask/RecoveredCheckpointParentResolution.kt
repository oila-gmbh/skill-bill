package skillbill.engine.featuretask

import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.ports.workflow.gitops.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE

internal data class RecoveredCheckpointParentResolution(
  val parentSha: String?,
  val failure: String?,
)

internal fun resolveRecoveredCheckpointParent(
  request: MissingCheckpointIdentityRequest,
): RecoveredCheckpointParentResolution {
  val runLoop = request.runLoop
  val ledger = SubtaskCommitLedgerState(
    commitSha = request.relevant.lastOrNull()?.commitSha,
    nextSequenceNumber = (request.identities.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    branch = request.relevant.lastOrNull()?.branch,
  )
  val recovered = runLoop.phaseGates.gitOperations.recoveredSubtaskParent(
    RecoveredSubtaskParentRequest(
      repoRoot = runLoop.request.repoRoot,
      headSha = request.headSha,
      branch = request.branch,
      identity = request.identity,
      sequenceNumber = ledger.nextSequenceNumber,
      prior = request.relevant.lastOrNull(),
    ),
  )
  if (recovered.ok) {
    return RecoveredCheckpointParentResolution(recovered.value.orEmpty().trim(), null)
  }
  val parent = runLoop.phaseGates.gitOperations.resolveCommit(
    runLoop.request.repoRoot,
    "${request.headSha}^",
  )
  val parentSha = parent.value.orEmpty().trim()
  if (parent.ok && parentSha.isNotBlank()) {
    runCatching {
      runLoop.diagnostics.warning(
        "record_kind=migration seam=FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger." +
          "recoverMissingCheckpointIdentity value_used='${request.headSha}' parent='$parentSha' " +
          "cause=${recovered.error}; defaulting owned parent to HEAD^",
      )
    }
    return RecoveredCheckpointParentResolution(parentSha, null)
  }
  return RecoveredCheckpointParentResolution(null, recovered.error)
}

internal fun persistRecoveredCheckpointParentRef(
  request: MissingCheckpointIdentityRequest,
  parentSha: String,
): Boolean {
  val runLoop = request.runLoop
  val sequenceNumber = (request.identities.maxOfOrNull { it.sequenceNumber } ?: -1) + 1
  val refName = request.identity.checkpointRefName(sequenceNumber)
  val existing = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  if (!existing.ok) {
    return recoveredCheckpointRefBlock(
      request,
      refName,
      "could not be inspected (${existing.error}); operator decision: repair checkpoint ref access before reviewing",
    )
  }
  val occupant = existing.value.orEmpty().trim()
  if (occupant.isNotBlank() && occupant != parentSha) {
    return recoveredCheckpointRefBlock(
      request,
      refName,
      "already names '$occupant' instead of parent '$parentSha'; operator decision: resolve the foreign recovery ref " +
        "before reviewing",
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
      return recoveredCheckpointRefBlock(
        request,
        refName,
        "could not be written (${written.error}); operator decision: repair checkpoint ref access before reviewing",
      )
    }
  }
  val verified = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  return if (verified.ok && verified.value.orEmpty().trim() == parentSha) {
    true
  } else {
    recoveredCheckpointRefBlock(
      request,
      refName,
      "did not verify as parent '$parentSha'; operator decision: repair the recovery refs before reviewing",
    )
  }
}

private fun recoveredCheckpointRefBlock(
  request: MissingCheckpointIdentityRequest,
  refName: String,
  reason: String,
): Boolean = blockLedgerReconciliation(
  request.runLoop,
  BlockReconciliationRequest(
    request.precedingPhaseId,
    request.branch,
    request.blockedReason,
    "recovered checkpoint ref '$refName' $reason",
  ),
)
