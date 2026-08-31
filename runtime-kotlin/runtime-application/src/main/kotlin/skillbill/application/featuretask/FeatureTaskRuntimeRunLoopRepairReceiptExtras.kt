package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.stagePaths

internal fun FeatureTaskRuntimeRunLoop.settleCompletedImplementationOutput(
  args: CompletedImplementationOutputArgs,
): AttemptResult? = settleAndPersistImplementFixRepairReceipt(
  ImplementFixRepairReceiptArgs(
    run = args.run,
    outputMap = args.outputMap,
    reject = args.reject,
    iteration = args.iteration,
    observability = args.observability,
    fileManifest = args.fileManifest,
  ),
)

internal fun FeatureTaskRuntimeRunLoop.blockRemediationBaseSha(precedingPhaseId: String, error: String): Boolean {
  blockAt(
    precedingPhaseId,
    "Feature-task-runtime could not record the pre-fix remediation base sha before re-entering " +
      "implement_fix" + (if (error.isBlank()) "." else " ($error).") +
      " Without it the reserved remediation pass would silently review the full base-to-current " +
      "delta instead of the remediation delta.",
  )
  return false
}

/**
 * Stages exactly [ownedPaths] and commits them. The pre-checkpoint index is snapshotted first, so a
 * staging or commit failure restores the index to what it was rather than leaving a partial
 * mutation that would silently ride along in the user's next commit. The working tree is never
 * touched on any path through here.
 */
internal fun FeatureTaskRuntimeRunLoop.commitCheckpoint(args: CommitCheckpointArgs): Boolean {
  val precedingPhaseId = args.precedingPhaseId
  val branch = args.branch
  val loopId = args.loopId
  val intent = args.intent
  val ownedPaths = args.ownedPaths
  val blockedReason = args.blockedReason
  val snapshot = phaseGates.gitOperations.captureIndexState(request.repoRoot, ownedPaths)
  if (!snapshot.ok) {
    return blockCheckpoint(precedingPhaseId, branch, snapshot.error, blockedReason)
  }
  val parentSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
  val staged = phaseGates.gitOperations.stagePaths(request.repoRoot, ownedPaths)
  if (!staged.ok) {
    return blockCheckpoint(
      precedingPhaseId,
      branch,
      withIndexRestoreOutcome(staged.error, ownedPaths, snapshot.value.orEmpty()),
      blockedReason,
    )
  }
  val subtaskIdentity = subtaskCommitIdentity()
  val message = checkpointCommitMessage(
    branch = branch,
    phaseId = precedingPhaseId,
    loopId = loopId,
    identity = subtaskIdentity,
    intent = intent,
  )
  val commit = writeSubtaskCommit(branch, message, subtaskIdentity)
  if (!commit.ok) {
    return blockCheckpoint(
      precedingPhaseId,
      branch,
      withIndexRestoreOutcome(commit.error, ownedPaths, snapshot.value.orEmpty()),
      blockedReason,
    )
  }
  return recordCheckpointIdentity(
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

/**
 * The subtask every checkpoint of this run belongs to. A standalone feature-task run owns no
 * decomposed subtask; the reserved literal keeps one commit and one ref namespace per run anyway.
 */
