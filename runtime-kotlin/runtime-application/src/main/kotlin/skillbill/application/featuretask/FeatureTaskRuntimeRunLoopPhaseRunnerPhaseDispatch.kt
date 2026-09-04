package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

@Inject
class FeatureTaskRuntimeRunLoopPhaseRunnerPhaseDispatch {
  fun isReenterableLaunchSeamRecordRejection(phaseId: String, reason: String): Boolean =
    reason.contains(LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION) &&
      FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER.containsKey(phaseId)

  // A launch-seam record rejection never ran the consumer, so its attempts are not real fix-loop output
  // attempts. Re-enterable whether the block still carries the launch-seam reason or was already
  // overwritten with the generic fix-loop-exhaustion text on a prior re-entry (recognized from the ledger).
  fun isReenterableRecordRejection(state: FeatureTaskRuntimeRunState, phaseId: String, reason: String): Boolean =
    isReenterableLaunchSeamRecordRejection(phaseId, reason) ||
      state.legacyLaunchSeamRejectionConsumedBudget(phaseId, reason)

  // Decides whether a phase with a persisted block relaunches instead of re-surfacing it, restarting the
  // fix-loop budget for the re-enterable stale-block classes whose prior attempts were not real semantic
  // output failures (goal-review preparation retries, launch-seam record rejections, and the removed
  // implementation-continuation segment cap).
  fun shouldRelaunchPersistedBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    state: FeatureTaskRuntimeRunState,
    phaseId: String,
    durable: FeatureTaskRuntimePhaseRecord?,
    persistedReason: String,
  ): Boolean {
    val retryReviewPreparation = runLoop.collaborators.phaseRunner.isRetryableGoalReviewPreparation(
      phaseId,
      persistedReason,
    ) ||
      state.legacyReviewPreparationRetryConsumedBudget(phaseId, persistedReason)
    val reenterableRecordRejection = isReenterableRecordRejection(state, phaseId, persistedReason)
    val removedContinuationBudget =
      runLoop.collaborators.phaseRunner.isRemovedImplementationContinuationBudgetBlock(phaseId, persistedReason)
    val restartsBudget = listOf(
      retryReviewPreparation,
      reenterableRecordRejection,
      removedContinuationBudget,
      runLoop.collaborators.phaseAttemptsContinued1.operatorReopenedPhase(runLoop, phaseId),
    ).any { it }
    if (restartsBudget) {
      state.restartAttemptBudget(phaseId)
    }
    return shouldRetryPersistedBlock(
      runLoop,
      ShouldRetryPersistedBlockArgs(
        phaseId = phaseId,
        durable = durable,
        retryReviewPreparation = retryReviewPreparation,
        reenterableRecordRejection = reenterableRecordRejection,
        persistedReason = persistedReason,
      ),
    )
  }

  internal fun shouldRetryPersistedBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ShouldRetryPersistedBlockArgs,
  ): Boolean {
    val phaseId = args.phaseId
    val durable = args.durable
    val retryReviewPreparation = args.retryReviewPreparation
    val reenterableRecordRejection = args.reenterableRecordRejection
    val persistedReason = args.persistedReason
    val disposition = durable?.failureDisposition
    return when {
      // Ahead of every disposition check: an operator reopen is a decision about this exact block,
      // whatever its class or disposition, so no persisted reason may veto it.
      runLoop.collaborators.phaseAttemptsContinued1.operatorReopenedPhase(runLoop, phaseId) -> true
      retryReviewPreparation -> true
      reenterableRecordRejection -> true
      runLoop.collaborators.phaseRunner.isRemovedGoalReviewSchemaGateBlock(phaseId, persistedReason) -> true
      runLoop.collaborators.phaseRunner.isRemovedImplementationContinuationBudgetBlock(
        phaseId,
        persistedReason,
      ) -> true
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        persistedReason.startsWith("Audit-gap recovery requires") -> true
      disposition != null -> disposition.retryOnResume
      else -> FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(phaseId)
    }
  }

  internal fun prepareGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = when {
    run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> GoalReviewRunReady(run)
    runLoop.collaborators.outputPersistence.isGoalReviewRun(run) ->
      runLoop.collaborators.phaseRunnerContinued2.reserveGoalReviewRun(runLoop, run, observability)
    else -> prepareStandaloneReviewRun(runLoop, run, observability)
  }

  internal fun prepareStandaloneReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation {
    val resolved = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
      ?: return runLoop.collaborators.phaseRunnerContinued2.blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        "Standalone review is missing its durable resolved branch.",
      )
    val reviewBaseSha = resolved.reviewBaseSha
      ?: return runLoop.collaborators.phaseRunnerContinued2.blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        "Standalone review is missing the immutable review base captured before implementation.",
      )
    val result = runLoop.phaseGates.gitOperations.buildGoalSubtaskReviewInput(
      run.request.repoRoot,
      FeatureTaskRuntimeScopedReviewBaseline.of(
        runLoop.phaseGates.gitOperations,
        run.request.repoRoot,
        resolved,
        reviewBaseSha,
      ),
      resolved.branch,
    )
    val input = result.input
      ?: return runLoop.collaborators.phaseRunnerContinued2.blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        result.error.ifBlank { "Standalone review input failed." },
      )
    return GoalReviewRunReady(run.copy(goalReviewInput = input))
  }

  /**
   * Review scope is the checkpoint's owned inventory, not whatever the worktree happens to hold. The
   * persisted inventory is the same one the checkpoint identity digested, so the input a review sees
   * is reproducible from the immutable commit rather than from the tree's current dirt.
   */
  fun scopedReviewUntrackedExclusions(
    runLoop: FeatureTaskRuntimeRunLoop,
    resolved: FeatureTaskRuntimeResolvedBranch,
  ): List<String> = FeatureTaskRuntimeScopedReviewBaseline.untrackedExclusions(
    runLoop.phaseGates.gitOperations,
    runLoop.request.repoRoot,
    resolved,
  )
}
