package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.workflow.goal.model.GoalSubtaskReviewState

internal fun FeatureTaskRuntimeRunLoopRepairReceipt.repairReceiptAnchor(
  runLoop: FeatureTaskRuntimeRunLoop,
  reviewState: GoalSubtaskReviewState,
): RepairReceiptAnchor? {
  val baseSha = reviewState.remediationBaseSha
  val roundNumber = featureTaskRuntimeRemediationRoundNumberOrNull(reviewState)
  if (baseSha == null || roundNumber == null) {
    recordRepairReceiptDegradation(
      runLoop,
      if (baseSha == null) {
        "no durable remediation base sha was recorded for this round"
      } else {
        "the durable remediation round number is not yet established"
      },
    )
    return null
  }
  return RepairReceiptAnchor(baseSha = baseSha, roundNumber = roundNumber)
}

fun FeatureTaskRuntimeRunLoopRepairReceipt.recordRepairReceiptDegradation(
  runLoop: FeatureTaskRuntimeRunLoop,
  reason: String,
) {
  runCatching {
    runLoop.diagnostics.warning(
      "Feature-task-runtime did not record the implement_fix repair receipt for issue " +
        "${runLoop.request.issueKey}, workflow ${runLoop.request.workflowId}: $reason. The remediation repair " +
        "ledger loses this round.",
    )
  }
}

internal fun FeatureTaskRuntimeRunLoopRepairReceipt.settleCompletedImplementationOutput(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: CompletedImplementationOutputArgs,
): AttemptResult? = settleAndPersistImplementFixRepairReceipt(
  runLoop,
  ImplementFixRepairReceiptArgs(
    run = args.run,
    outputMap = args.outputMap,
    reject = args.reject,
    iteration = args.iteration,
    observability = args.observability,
    fileManifest = args.fileManifest,
  ),
)

fun FeatureTaskRuntimeRunLoopRepairReceipt.blockRemediationBaseSha(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  error: String,
): Boolean {
  runLoop.collaborators.planningBranch.blockAt(
    runLoop,
    precedingPhaseId,
    "Feature-task-runtime could not record the pre-fix remediation base sha before re-entering " +
      "implement_fix" + (if (error.isBlank()) "." else " ($error).") +
      " Without it the reserved remediation pass would silently review the full base-to-current " +
      "delta instead of the remediation delta.",
  )
  return false
}

private fun blockCheckpointAfterIndexMutation(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: CommitCheckpointArgs,
  error: String,
  indexSnapshot: String,
): Boolean = runLoop.collaborators.checkpointContinued6.blockCheckpoint(
  runLoop,
  args.precedingPhaseId,
  args.branch,
  runLoop.collaborators.checkpointContinued5.withIndexRestoreOutcome(
    runLoop,
    error,
    args.ownedPaths,
    indexSnapshot,
  ),
  args.blockedReason,
)

internal fun FeatureTaskRuntimeRunLoopRepairReceipt.commitCheckpoint(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: CommitCheckpointArgs,
): Boolean = commitCheckpointWithCapturedIndex(runLoop, args)

private fun commitCheckpointWithCapturedIndex(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: CommitCheckpointArgs,
): Boolean {
  val snapshot = runLoop.phaseGates.gitOperations.captureIndexState(runLoop.request.repoRoot, args.ownedPaths)
  if (!snapshot.ok) {
    return runLoop.collaborators.checkpointContinued6.blockCheckpoint(
      runLoop,
      args.precedingPhaseId,
      args.branch,
      snapshot.error,
      args.blockedReason,
    )
  }
  val staged = runLoop.phaseGates.gitOperations.stagePaths(runLoop.request.repoRoot, args.ownedPaths)
  return if (!staged.ok) {
    blockCheckpointAfterIndexMutation(runLoop, args, staged.error, snapshot.value.orEmpty())
  } else {
    commitCheckpointAfterStaging(runLoop, args, snapshot.value.orEmpty())
  }
}

private fun commitCheckpointAfterStaging(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: CommitCheckpointArgs,
  indexSnapshot: String,
): Boolean {
  val identity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
  val message = runLoop.collaborators.checkpointContinued4.checkpointCommitMessage(
    runLoop,
    CheckpointCommitMessageArgs(args.branch, args.precedingPhaseId, args.loopId, identity, args.intent),
  )
  val commit = runLoop.collaborators.checkpointContinued5.writeSubtaskCommit(
    runLoop,
    args.branch,
    message,
    identity,
    args.ownedPaths,
  )
  if (!commit.ok) return blockCheckpointAfterIndexMutation(runLoop, args, commit.error, indexSnapshot)
  val commitSha = commit.value.orEmpty().trim()
  if (commitSha.isBlank()) {
    return blockCheckpointAfterIndexMutation(
      runLoop,
      args,
      "checkpoint commit returned an empty sha",
      indexSnapshot,
    )
  }
  val parentSha = runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, "$commitSha^")
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    ?: return blockCheckpointAfterIndexMutation(
      runLoop,
      args,
      "checkpoint commit '$commitSha' has no resolvable parent for durable identity",
      indexSnapshot,
    )
  return runLoop.collaborators.checkpointContinued5.recordCheckpointIdentity(
    runLoop,
    RecordCheckpointIdentityArgs(
      args.precedingPhaseId,
      args.branch,
      args.loopId,
      args.ownedPaths,
      parentSha,
      commitSha,
      args.blockedReason,
    ),
  )
}
