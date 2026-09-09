package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.ports.workflow.gitops.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal fun migrationWarning(runLoop: FeatureTaskRuntimeRunLoop, expected: String, cause: String?) {
  runLoop.diagnostics.warning(
    "record_kind=refusal seam=FeatureTaskRuntimeSubtaskCommitMigrationNormalizer.rollback " +
      "value_used='rollback' value_expected=$expected cause=${cause.orEmpty()}",
  )
}

internal fun validateMigrationBase(runLoop: FeatureTaskRuntimeRunLoop, firstParent: String?): String? {
  val parentSha = firstParent ?: return "the active checkpoint span has no parent SHA; operator decision: identify " +
    "the span base before normalizing"
  val parent = runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, parentSha)
  return if (parent.ok) {
    null
  } else {
    "active span parent '$parentSha' could not be resolved; operator decision: " +
      "restore the branch base before normalizing"
  }
}

internal fun preserveSubtaskMigrationCommits(
  runLoop: FeatureTaskRuntimeRunLoop,
  active: List<FeatureTaskRuntimeCheckpointIdentity>,
  firstRecoverySequence: Int,
): String? = active.mapIndexedNotNull { index, checkpoint ->
  preserveMigrationCheckpoint(runLoop, checkpoint, firstRecoverySequence + index)
}.firstOrNull()

private fun preserveMigrationCheckpoint(
  runLoop: FeatureTaskRuntimeRunLoop,
  checkpoint: FeatureTaskRuntimeCheckpointIdentity,
  sequence: Int,
): String? {
  val refName = FeatureTaskRuntimeSubtaskCommitIdentity(checkpoint.issueKey, checkpoint.subtaskId)
    .checkpointRefName(sequence)
  val existing = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  if (!existing.ok) {
    return "checkpoint ref '$refName' could not be inspected (${existing.error}); operator decision: repair " +
      "checkpoint ref access before normalizing"
  }
  val occupant = existing.value.orEmpty().trim()
  if (occupant.isNotBlank() && occupant != checkpoint.commitSha) {
    return "checkpoint ref '$refName' already names '$occupant' instead of '${checkpoint.commitSha}'; operator " +
      "decision: resolve the foreign ref before normalizing"
  }
  val updated = runLoop.phaseGates.gitOperations.updateCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
    checkpoint.commitSha,
  )
  if (!updated.ok) {
    return "checkpoint ref '$refName' could not preserve '${checkpoint.commitSha}' (${updated.error}); operator " +
      "decision: preserve the ref before normalizing"
  }
  val verified = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  return if (verified.ok && verified.value.orEmpty().trim() == checkpoint.commitSha) {
    null
  } else {
    "checkpoint ref '$refName' did not verify after preservation; operator decision: restore the branch from the " +
      "preserved refs"
  }
}
