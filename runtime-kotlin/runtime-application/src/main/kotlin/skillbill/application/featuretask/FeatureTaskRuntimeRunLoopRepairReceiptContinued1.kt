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

private fun FeatureTaskRuntimeRunLoopRepairReceipt.blockCheckpointAfterIndexMutation(
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
): Boolean {
  val precedingPhaseId = args.precedingPhaseId
  val branch = args.branch
  val loopId = args.loopId
  val intent = args.intent
  val ownedPaths = args.ownedPaths
  val blockedReason = args.blockedReason
  val snapshot = runLoop.phaseGates.gitOperations.captureIndexState(runLoop.request.repoRoot, ownedPaths)
  if (!snapshot.ok) {
    return runLoop.collaborators.checkpointContinued6.blockCheckpoint(
      runLoop,
      precedingPhaseId,
      branch,
      snapshot.error,
      blockedReason,
    )
  }
  val parentSha = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
  val staged = runLoop.phaseGates.gitOperations.stagePaths(runLoop.request.repoRoot, ownedPaths)
  if (!staged.ok) {
    return blockCheckpointAfterIndexMutation(runLoop, args, staged.error, snapshot.value.orEmpty())
  }
  val subtaskIdentity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
  val message = runLoop.collaborators.checkpointContinued4.checkpointCommitMessage(
    runLoop,
    CheckpointCommitMessageArgs(
      branch = branch,
      phaseId = precedingPhaseId,
      loopId = loopId,
      identity = subtaskIdentity,
      intent = intent,
    ),
  )
  val commit = runLoop.collaborators.checkpointContinued5.writeSubtaskCommit(runLoop, branch, message, subtaskIdentity)
  if (!commit.ok) {
    return blockCheckpointAfterIndexMutation(runLoop, args, commit.error, snapshot.value.orEmpty())
  }
  return runLoop.collaborators.checkpointContinued5.recordCheckpointIdentity(
    runLoop,
    RecordCheckpointIdentityArgs(
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = ownedPaths,
      parentSha = parentSha,
      commitSha = commit.value.orEmpty().trim(),
      blockedReason = blockedReason,
    ),
  )
}
