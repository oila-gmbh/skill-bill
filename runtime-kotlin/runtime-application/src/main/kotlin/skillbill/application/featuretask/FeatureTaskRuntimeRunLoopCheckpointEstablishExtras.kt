package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal fun FeatureTaskRuntimeRunLoop.remediationCheckpointSkippable(): Boolean {
  val branch = resolvedBranch
  return branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null
}

internal fun FeatureTaskRuntimeRunLoop.remediationCheckpointOffBranch(branch: String): Boolean {
  val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
  return !head.ok || head.value.trim() != branch.trim()
}

internal fun FeatureTaskRuntimeRunLoop.establishRemediationCheckpointStage(
  precedingPhaseId: String,
  branch: String,
  loopId: String,
  scope: FeatureTaskRuntimeCheckpointDecision.Stage,
): Boolean {
  if (scope.adoptedPaths.isNotEmpty()) {
    runCatching {
      diagnostics.warning(FeatureTaskRuntimeCheckpointScope.adoptionWarning(branch, scope.adoptedPaths))
    }
  }
  val committed = commitRemediationCheckpoint(
    precedingPhaseId = precedingPhaseId,
    branch = branch,
    loopId = loopId,
    ownedPaths = scope.ownedPaths,
  ) ?: return false
  return recordRemediationBaseIfNeeded(
    precedingPhaseId = precedingPhaseId,
    loopId = loopId,
    commitSha = committed.commitSha,
    parentSha = committed.parentSha,
  )
}

internal data class RemediationCommitPrepared(
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String,
  val ownedPaths: List<String>,
  val indexSnapshot: String,
  val parentSha: String?,
  val subtaskIdentity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val message: String,
)

internal fun FeatureTaskRuntimeRunLoop.prepareRemediationCommit(
  precedingPhaseId: String,
  branch: String,
  loopId: String,
  ownedPaths: List<String>,
): RemediationCommitPrepared? {
  val snapshot = phaseGates.gitOperations.captureIndexState(request.repoRoot, ownedPaths)
  if (!snapshot.ok) {
    blockCheckpoint(precedingPhaseId, branch, snapshot.error, ::remediationCheckpointBlockedReason)
    return null
  }
  val parentSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
  val staged = phaseGates.gitOperations.stagePaths(request.repoRoot, ownedPaths)
  if (!staged.ok) {
    blockCheckpoint(
      precedingPhaseId,
      branch,
      withIndexRestoreOutcome(staged.error, ownedPaths, snapshot.value.orEmpty()),
      ::remediationCheckpointBlockedReason,
    )
    return null
  }
  val subtaskIdentity = subtaskCommitIdentity()
  val message = checkpointCommitMessage(
    branch = branch,
    phaseId = precedingPhaseId,
    loopId = loopId,
    identity = subtaskIdentity,
    intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
  )
  return RemediationCommitPrepared(
    precedingPhaseId = precedingPhaseId,
    branch = branch,
    loopId = loopId,
    ownedPaths = ownedPaths,
    indexSnapshot = snapshot.value.orEmpty(),
    parentSha = parentSha,
    subtaskIdentity = subtaskIdentity,
    message = message,
  )
}

internal fun FeatureTaskRuntimeRunLoop.finalizeRemediationCommit(
  prepared: RemediationCommitPrepared,
): RemediationCheckpointCommit? {
  val commit = writeSubtaskCommit(prepared.branch, prepared.message, prepared.subtaskIdentity)
  if (!commit.ok) {
    blockCheckpoint(
      prepared.precedingPhaseId,
      prepared.branch,
      withIndexRestoreOutcome(commit.error, prepared.ownedPaths, prepared.indexSnapshot),
      ::remediationCheckpointBlockedReason,
    )
    return null
  }
  val commitSha = commit.value.orEmpty().trim()
  if (commitSha.isBlank()) {
    blockCheckpoint(
      prepared.precedingPhaseId,
      prepared.branch,
      "remediation checkpoint commit returned an empty sha",
      ::remediationCheckpointBlockedReason,
    )
    return null
  }
  val recorded = recordCheckpointIdentity(
    RecordCheckpointIdentityArgs(
      precedingPhaseId = prepared.precedingPhaseId,
      branch = prepared.branch,
      loopId = prepared.loopId,
      ownedPaths = prepared.ownedPaths,
      parentSha = prepared.parentSha,
      commitSha = commitSha,
      blockedReason = ::remediationCheckpointBlockedReason,
    ),
  )
  if (!recorded) {
    rollbackRemediationCheckpointCommit(commitSha, prepared.parentSha, identityRecorded = false)
    return null
  }
  return RemediationCheckpointCommit(commitSha = commitSha, parentSha = prepared.parentSha)
}

internal fun FeatureTaskRuntimeRunLoop.checkpointIdentitiesForRollback(
  commitSha: String,
): List<FeatureTaskRuntimeCheckpointIdentity> {
  require(commitSha.isNotBlank()) { "rollback requires a non-blank commit sha" }
  val subtaskId = request.goalContinuation?.subtaskId?.toString()
    ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
  return runCatching {
    recorder.loadCheckpointIdentities(request.workflowId, request.dbPathOverride)
  }.fold(
    onSuccess = { loaded -> loaded.orEmpty() },
    onFailure = { error ->
      recordRemediationRollbackDegradation(
        seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
        valueUsed = request.workflowId,
        valueExpected = "checkpoint identities for rollback",
        cause = "loadCheckpointIdentities failed: " +
          error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() },
      )
      emptyList()
    },
  )
    .filter { it.issueKey == request.issueKey && it.subtaskId == subtaskId }
    .sortedBy { it.sequenceNumber }
}
