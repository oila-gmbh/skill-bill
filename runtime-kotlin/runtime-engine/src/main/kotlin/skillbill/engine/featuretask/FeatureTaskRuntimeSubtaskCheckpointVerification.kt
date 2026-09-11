package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.commitMessage
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal fun FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger.verifyCurrentCheckpointTree(
  runLoop: FeatureTaskRuntimeRunLoop,
  identity: FeatureTaskRuntimeCheckpointIdentity,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): Boolean {
  val tree = runLoop.phaseGates.gitOperations.resolveTree(
    runLoop.request.repoRoot,
    identity.commitSha,
  )
  val parent = runLoop.phaseGates.gitOperations.resolveCommit(
    runLoop.request.repoRoot,
    "${identity.commitSha}^",
  )
  val ref = runLoop.phaseGates.gitOperations.resolveCheckpointRef(
    runLoop.request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    identity.checkpointRef,
  )
  val refTarget = ref.value.orEmpty().trim()
  val expectedRefTarget = identity.parentSha ?: identity.commitSha
  val preservedAmendment = if (ref.ok && refTarget != expectedRefTarget && refTarget.isNotBlank()) {
    val message = runLoop.phaseGates.gitOperations.commitMessage(runLoop.request.repoRoot, refTarget)
    val preservedParent = runLoop.phaseGates.gitOperations.resolveCommit(
      runLoop.request.repoRoot,
      "$refTarget^",
    )
    message.ok && FeatureTaskRuntimeSubtaskCommitIdentity(identity.issueKey, identity.subtaskId)
      .matches(message.value.orEmpty()) && preservedParent.ok &&
      preservedParent.value.orEmpty().trim() == expectedRefTarget
  } else {
    false
  }
  val evidence = CheckpointTreeEvidence(
    treeOk = tree.ok,
    treeSha = tree.value,
    parentOk = parent.ok,
    parentSha = parent.value,
    identity = identity,
    refOk = ref.ok,
    refTarget = refTarget,
    expectedRefTarget = expectedRefTarget,
    preservedAmendment = preservedAmendment,
  )
  return if (checkpointTreeMatches(evidence)) {
    true
  } else {
    blockReconciliation(
      runLoop,
      BlockReconciliationRequest(
        precedingPhaseId = precedingPhaseId,
        branch = branch,
        blockedReason = blockedReason,
        reason = "durable checkpoint '${identity.commitSha}' has no resolvable tree or matching checkpoint ref; " +
          "operator decision: repair Git object or checkpoint-ref access before reviewing",
      ),
    )
  }
}

private fun checkpointTreeMatches(evidence: CheckpointTreeEvidence): Boolean = evidence.treeOk &&
  evidence.treeSha.orEmpty().isNotBlank() &&
  evidence.parentOk &&
  evidence.parentSha.orEmpty().trim() == evidence.identity.parentSha.orEmpty().trim() &&
  evidence.refOk &&
  (evidence.refTarget == evidence.expectedRefTarget || evidence.preservedAmendment)

private data class CheckpointTreeEvidence(
  val treeOk: Boolean,
  val treeSha: String?,
  val parentOk: Boolean,
  val parentSha: String?,
  val identity: FeatureTaskRuntimeCheckpointIdentity,
  val refOk: Boolean,
  val refTarget: String,
  val expectedRefTarget: String,
  val preservedAmendment: Boolean,
)
