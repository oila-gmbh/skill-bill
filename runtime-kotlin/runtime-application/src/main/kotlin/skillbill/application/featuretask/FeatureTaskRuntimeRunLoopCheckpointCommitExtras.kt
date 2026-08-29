package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun FeatureTaskRuntimeRunLoop.recordCheckpointIdentity(args: RecordCheckpointIdentityArgs): Boolean {
  val precedingPhaseId = args.precedingPhaseId
  val branch = args.branch
  val loopId = args.loopId
  val ownedPaths = args.ownedPaths
  val parentSha = args.parentSha
  val commitSha = args.commitSha
  val blockedReason = args.blockedReason
  val recorded = runCatching {
    recorder.appendCheckpointIdentity(
      AppendCheckpointIdentityArgs(
        workflowId = request.workflowId,
        issueKey = request.issueKey,
        subtaskId = request.goalContinuation?.subtaskId?.toString()
          ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
        branch = branch,
        phaseId = precedingPhaseId,
        loopId = loopId,
        generation = checkpointGeneration(loopId),
        parentSha = parentSha,
        ownedPaths = ownedPaths,
        commitSha = commitSha,
        dbOverride = request.dbPathOverride,
      ),
    )
  }
  // The commit already exists; without its identity record the review input has no immutable
  // checkpoint to build from and no later phase can prove what this commit was allowed to own.
  return if (recorded.getOrDefault(false)) {
    true
  } else {
    blockCheckpoint(
      precedingPhaseId,
      branch,
      "checkpoint commit '$commitSha' was created but its durable identity record could not be " +
        "written (${recorded.exceptionOrNull()?.message ?: "the workflow row was absent"}), so the " +
        "commit cannot be attributed to this workflow's authority boundary",
      blockedReason,
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.blockCheckpoint(
  precedingPhaseId: String,
  branch: String,
  error: String,
  blockedReason: (String, String) -> String,
): Boolean {
  blockAt(precedingPhaseId, blockedReason(branch, error))
  return false
}

internal fun FeatureTaskRuntimeRunLoop.matchingBackwardEdge(
  phaseId: String,
  verdict: FeatureTaskRuntimeVerdict,
): FeatureTaskRuntimeBackwardEdge? =
  transitions.backwardEdges.firstOrNull { it.fromPhaseId == phaseId && it.triggeringVerdict == verdict }

/**
 * Record-only resume reconstruction: a durable fix record carries this loop's context at the current
 * watermark but no `LOOP_EDGE` ledger row reconstructed it as in-flight, so the reserved iteration is
 * re-entered instead of a fresh one being allocated (no double-applied mutation). It is one-shot per
 * run — the loop is live-claimed the moment either this path or a live edge fire mints an iteration.
 * Without that bound the unbounded loop would re-satisfy this reconstruction on every re-review and
 * keep replaying the already-reviewed fix instead of earning the next remediation pass.
 */
