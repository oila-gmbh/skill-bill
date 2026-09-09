package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.workflow.gitops.commitMessage
import skillbill.ports.workflow.gitops.deleteCheckpointRef
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal data class SubtaskMigrationRollbackRequest(
  val headSha: String,
  val stagedPaths: List<String>,
  val snapshot: String,
  val originalIdentities: List<FeatureTaskRuntimeCheckpointIdentity>? = null,
  val replacementRefName: String? = null,
  val replacementRefTarget: String? = null,
)

internal fun rollbackSubtaskMigration(runLoop: FeatureTaskRuntimeRunLoop, request: SubtaskMigrationRollbackRequest) {
  resetMigrationHead(runLoop, request.headSha)
  restoreMigrationIndex(runLoop, request.stagedPaths, request.snapshot)
  restoreMigrationRef(runLoop, request.replacementRefName, request.replacementRefTarget)
  restoreMigrationIdentities(runLoop, request.originalIdentities)
}

private fun resetMigrationHead(runLoop: FeatureTaskRuntimeRunLoop, headSha: String) {
  val reset = runLoop.phaseGates.gitOperations.resetSoftToCommit(runLoop.request.repoRoot, headSha)
  if (!reset.ok) migrationWarning(runLoop, "the original active-subtask HEAD", reset.error)
}

private fun restoreMigrationIndex(runLoop: FeatureTaskRuntimeRunLoop, paths: List<String>, snapshot: String) {
  if (paths.isEmpty()) return
  val restored = runLoop.phaseGates.gitOperations.restoreIndexState(runLoop.request.repoRoot, paths, snapshot)
  if (!restored.ok) migrationWarning(runLoop, "the captured pre-normalization index", restored.error)
}

private fun restoreMigrationRef(runLoop: FeatureTaskRuntimeRunLoop, name: String?, target: String?) {
  if (name == null) return
  val restored = if (target.isNullOrBlank()) {
    runLoop.phaseGates.gitOperations.deleteCheckpointRef(
      runLoop.request.repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      name,
    )
  } else {
    runLoop.phaseGates.gitOperations.updateCheckpointRef(
      runLoop.request.repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      name,
      target,
    )
  }
  if (!restored.ok) migrationWarning(runLoop, "restored checkpoint ref '$name'", restored.error)
}

private fun restoreMigrationIdentities(
  runLoop: FeatureTaskRuntimeRunLoop,
  identities: List<FeatureTaskRuntimeCheckpointIdentity>?,
) {
  if (identities == null) return
  val restored = runCatching {
    runLoop.recorder.replaceCheckpointIdentities(
      runLoop.request.workflowId,
      identities,
      runLoop.request.dbPathOverride,
    )
  }.getOrDefault(false)
  if (!restored) migrationWarning(runLoop, "restored durable active span", "identity rollback failed")
}

internal fun validateSubtaskMigrationChain(
  runLoop: FeatureTaskRuntimeRunLoop,
  active: List<FeatureTaskRuntimeCheckpointIdentity>,
): String? {
  val checkpointFailure = active.mapIndexedNotNull { index, checkpoint ->
    validateMigrationCheckpoint(runLoop, active, index, checkpoint)
  }.firstOrNull()
  return checkpointFailure ?: validateMigrationBase(runLoop, active.firstOrNull()?.parentSha)
}

private fun validateMigrationCheckpoint(
  runLoop: FeatureTaskRuntimeRunLoop,
  active: List<FeatureTaskRuntimeCheckpointIdentity>,
  index: Int,
  checkpoint: FeatureTaskRuntimeCheckpointIdentity,
): String? {
  val resolved = runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, checkpoint.commitSha)
  if (!resolved.ok || resolved.value.orEmpty().trim() != checkpoint.commitSha) {
    return "active checkpoint '${checkpoint.commitSha}' could not be resolved; operator decision: repair the " +
      "checkpoint identity record before normalizing"
  }
  val message = runLoop.phaseGates.gitOperations.commitMessage(runLoop.request.repoRoot, checkpoint.commitSha)
  if (!message.ok || !FeatureTaskRuntimeSubtaskCommitIdentity(checkpoint.issueKey, checkpoint.subtaskId)
      .matches(message.value.orEmpty())
  ) {
    return "checkpoint '${checkpoint.commitSha}' lacks its recorded subtask trailer; operator decision: resolve " +
      "the ownership ambiguity before normalizing"
  }
  if (index > 0 && checkpoint.parentSha != active[index - 1].commitSha) {
    return "active checkpoint identities are not a contiguous commit span at '${checkpoint.commitSha}'; operator " +
      "decision: identify the exact active span before normalizing"
  }
  return validateMigrationRecoveryRef(runLoop, checkpoint)
}

private fun validateMigrationRecoveryRef(
  runLoop: FeatureTaskRuntimeRunLoop,
  checkpoint: FeatureTaskRuntimeCheckpointIdentity,
): String? {
  val ref = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    checkpoint.checkpointRef,
  )
  val target = ref.value.orEmpty().trim()
  val expected = checkpoint.parentSha.orEmpty().trim()
  val preserved = target.isNotBlank() && target != expected && ref.ok &&
    preservedMigrationParent(runLoop, checkpoint, target, expected)
  return if (!ref.ok || (target != expected && !preserved)) {
    "checkpoint ref '${checkpoint.checkpointRef}' does not prove the recorded parent; operator decision: repair " +
      "the recovery refs before normalizing"
  } else {
    null
  }
}

private fun preservedMigrationParent(
  runLoop: FeatureTaskRuntimeRunLoop,
  checkpoint: FeatureTaskRuntimeCheckpointIdentity,
  target: String,
  expected: String,
): Boolean {
  val message = runLoop.phaseGates.gitOperations.commitMessage(runLoop.request.repoRoot, target)
  val parent = runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, "$target^")
  return message.ok && FeatureTaskRuntimeSubtaskCommitIdentity(checkpoint.issueKey, checkpoint.subtaskId)
    .matches(message.value.orEmpty()) && parent.ok && parent.value.orEmpty().trim() == expected
}

internal data class SubtaskMigrationRefusalRequest(
  val precedingPhaseId: String,
  val branch: String,
  val blockedReason: (String, String) -> String,
  val reason: String,
  val cause: Throwable? = null,
)

internal fun refuseSubtaskMigration(
  runLoop: FeatureTaskRuntimeRunLoop,
  request: SubtaskMigrationRefusalRequest,
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
      "record_kind=${if (request.reason.contains("normaliz")) "migration" else "refusal"} " +
        "seam=FeatureTaskRuntimeSubtaskCommitMigrationNormalizer value_used='${request.branch}' " +
        "value_expected=provable active subtask commit span cause=${request.reason}",
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
