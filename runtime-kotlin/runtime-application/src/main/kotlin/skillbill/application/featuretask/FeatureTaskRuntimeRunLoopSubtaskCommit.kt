package skillbill.application.featuretask

import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.unownedWorktreeCommitSha(
  run: PhaseRun,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
): CommitPushFinalisation {
  val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
  val sha = head.value.orEmpty().trim().takeIf { head.ok && it.isNotBlank() }
    ?: return CommitPushNotApplicable
  runCatching {
    diagnostics.warning(
      "seam=FeatureTaskRuntimeRunLoop.finaliseSubtaskCommit value_used='measured HEAD $sha' " +
        "value_expected=a runtime-finalised subtask commit for '${request.issueKey}' " +
        "cause=the run has no resolved, unprotected, checked-out branch, so finalisation could not " +
        "stage, amend, or push and the commit sha degrades to whatever HEAD already names",
    )
  }
  return CommitPushSettled(
    revalidated(run.phaseId, FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(normalizedOutput.envelope, sha)),
  )
}

/**
 * The branch finalisation may write to: the run's own resolved, unprotected, currently checked-out
 * branch. Anything else means the runtime does not own this working tree, which is the same condition
 * under which no checkpoint ever committed here either.
 */
internal fun FeatureTaskRuntimeRunLoop.finalisationBranch(): String? {
  val branch = resolvedBranch?.takeIf { FeatureTaskRuntimeBranchSetup.protectedBranchName(it) == null }
    ?: return null
  val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
  return branch.takeIf { head.ok && head.value.trim() == branch.trim() }
}

/**
 * The decomposition manifest records the post-push commit sha only after the goal runner reconciles a
 * completed child, so finalisation usually sees null here and defers pruning to that boundary.
 */
internal fun FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity(
  phaseId: String,
  branch: String,
  ledger: SubtaskCommitLedgerState,
  commitSha: String,
  stagedPaths: List<String>,
): String? {
  val appended = runCatching {
    recorder.appendCheckpointIdentity(
      AppendCheckpointIdentityArgs(
        workflowId = request.workflowId,
        issueKey = request.issueKey,
        subtaskId = request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
        branch = branch,
        phaseId = phaseId,
        loopId = null,
        generation = checkpointGeneration(null),
        parentSha = ledger.commitSha,
        ownedPaths = stagedPaths,
        commitSha = commitSha,
        dbOverride = request.dbPathOverride,
      ),
    )
  }
  if (appended.getOrDefault(false)) return null
  val cause = appended.exceptionOrNull()?.message ?: "the workflow row was absent"
  runCatching {
    diagnostics.warning(
      "seam=FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity " +
        "value_used='no durable identity for finalised commit $commitSha' " +
        "value_expected=an appended checkpoint identity for '${request.issueKey}' " +
        "cause=$cause",
    )
  }
  return "needs_human: the finalised subtask commit '$commitSha' was written but its durable " +
    "checkpoint identity could not be recorded ($cause), so it was not pushed. Without that pointer " +
    "a resumed run would open a second commit for this subtask instead of amending this one. Repair " +
    "the workflow store and resume; the commit is already on the branch."
}

internal fun FeatureTaskRuntimeRunLoop.revalidated(
  phaseId: String,
  envelope: Map<String, Any?>,
): NormalizedFeatureTaskRuntimePhaseOutput = outputValidator
  .validatePhaseOutput(JsonSupport.mapToJsonString(envelope), sourceLabel = phaseId)
  .requireAcceptedOutput(phaseId)
  .normalizedOutput
