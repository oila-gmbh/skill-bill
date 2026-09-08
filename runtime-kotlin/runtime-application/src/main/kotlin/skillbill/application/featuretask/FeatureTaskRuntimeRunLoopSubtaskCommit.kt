package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.AppendCheckpointIdentityArgs
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopSubtaskCommit {
  internal fun unownedWorktreeCommitSha(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): CommitPushFinalisation {
    val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
    val sha = head.value.orEmpty().trim().takeIf { head.ok && it.isNotBlank() }
      ?: return CommitPushNotApplicable
    runCatching {
      runLoop.diagnostics.warning(
        "seam=FeatureTaskRuntimeRunLoop.finaliseSubtaskCommit value_used='measured HEAD $sha' " +
          "value_expected=a runtime-finalised subtask commit for '${runLoop.request.issueKey}' " +
          "cause=the run has no resolved, unprotected, checked-out branch, so finalisation could not " +
          "stage, amend, or push and the commit sha degrades to whatever HEAD already names",
      )
    }
    return CommitPushSettled(
      revalidated(
        runLoop,
        run.phaseId,
        FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(
          normalizedOutput.envelope,
          sha,
        ),
      ),
    )
  }

  /**
   * The branch finalisation may write to: the run's own resolved, unprotected, currently checked-out
   * branch. Anything else means the runtime does not own this working tree, which is the same condition
   * under which no checkpoint ever committed here either.
   */
  fun finalisationBranch(runLoop: FeatureTaskRuntimeRunLoop): String? {
    val branch = runLoop.session.resolvedBranch
      ?.takeIf { FeatureTaskRuntimeBranchSetup.protectedBranchName(it) == null }
      ?: return null
    val head = runLoop.phaseGates.gitOperations.currentBranch(runLoop.request.repoRoot)
    return branch.takeIf { head.ok && head.value.trim() == branch.trim() }
  }

  /**
   * The decomposition manifest records the post-push commit sha only after the goal runner reconciles a
   * completed child, so finalisation usually sees null here and defers pruning to that boundary.
   */
  internal fun recordFinalisedCheckpointIdentity(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: RecordFinalisedCheckpointIdentityArgs,
  ): String? {
    val phaseId = args.phaseId
    val branch = args.branch
    val ledger = args.ledger
    val commitSha = args.commitSha
    val stagedPaths = args.stagedPaths
    val appended = runCatching {
      runLoop.recorder.appendCheckpointIdentity(
        AppendCheckpointIdentityArgs(
          workflowId = runLoop.request.workflowId,
          issueKey = runLoop.request.issueKey,
          subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString()
            ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
          branch = branch,
          phaseId = phaseId,
          loopId = null,
          generation = runLoop.collaborators.checkpointContinued5.checkpointGeneration(runLoop, null),
          parentSha = ledger.commitSha,
          ownedPaths = stagedPaths,
          commitSha = commitSha,
          dbOverride = runLoop.request.dbPathOverride,
        ),
      )
    }
    if (appended.getOrDefault(false)) return null
    val cause = appended.exceptionOrNull()?.message ?: "the workflow row was absent"
    runCatching {
      runLoop.diagnostics.warning(
        "seam=FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity " +
          "value_used='no durable identity for finalised commit $commitSha' " +
          "value_expected=an appended checkpoint identity for '${runLoop.request.issueKey}' " +
          "cause=$cause",
      )
    }
    return "needs_human: the finalised subtask commit '$commitSha' was written but its durable " +
      "checkpoint identity could not be recorded ($cause), so it was not pushed. Without that pointer " +
      "a resumed run would open a second commit for this subtask instead of amending this one. Repair " +
      "the workflow store and resume; the commit is already on the branch."
  }

  fun revalidated(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    envelope: Map<String, Any?>,
  ): NormalizedFeatureTaskRuntimePhaseOutput = runLoop.outputValidator
    .validatePhaseOutput(JsonSupport.mapToJsonString(envelope), sourceLabel = phaseId)
    .requireAcceptedOutput(phaseId)
    .normalizedOutput
}
