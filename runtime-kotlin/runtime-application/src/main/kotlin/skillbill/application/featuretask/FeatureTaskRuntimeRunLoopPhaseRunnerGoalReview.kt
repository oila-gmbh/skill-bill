package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

internal fun FeatureTaskRuntimeRunLoop.prepareGoalReviewRun(
  run: PhaseRun,
  observability: FeatureTaskRuntimeRunObservability,
): GoalReviewRunPreparation = when {
  run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> GoalReviewRunReady(run)
  isGoalReviewRun(run) -> reserveGoalReviewRun(run, observability)
  else -> prepareStandaloneReviewRun(run, observability)
}

internal fun FeatureTaskRuntimeRunLoop.prepareStandaloneReviewRun(
  run: PhaseRun,
  observability: FeatureTaskRuntimeRunObservability,
): GoalReviewRunPreparation {
  val resolved = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    ?: return blockedGoalReviewRun(run, observability, "Standalone review is missing its durable resolved branch.")
  val reviewBaseSha = resolved.reviewBaseSha
    ?: return blockedGoalReviewRun(
      run,
      observability,
      "Standalone review is missing the immutable review base captured before implementation.",
    )
  val result = phaseGates.gitOperations.buildGoalSubtaskReviewInput(
    run.request.repoRoot,
    FeatureTaskRuntimeScopedReviewBaseline.of(
      phaseGates.gitOperations,
      run.request.repoRoot,
      resolved,
      reviewBaseSha,
    ),
    resolved.branch,
  )
  val input = result.input
    ?: return blockedGoalReviewRun(run, observability, result.error.ifBlank { "Standalone review input failed." })
  return GoalReviewRunReady(run.copy(goalReviewInput = input))
}

/**
 * Review scope is the checkpoint's owned inventory, not whatever the worktree happens to hold. The
 * persisted inventory is the same one the checkpoint identity digested, so the input a review sees
 * is reproducible from the immutable commit rather than from the tree's current dirt.
 */
internal fun FeatureTaskRuntimeRunLoop.scopedReviewUntrackedExclusions(
  resolved: FeatureTaskRuntimeResolvedBranch,
): List<String> = FeatureTaskRuntimeScopedReviewBaseline.untrackedExclusions(
  phaseGates.gitOperations,
  request.repoRoot,
  resolved,
)

internal fun FeatureTaskRuntimeRunLoop.reserveGoalReviewRun(
  run: PhaseRun,
  observability: FeatureTaskRuntimeRunObservability,
): GoalReviewRunPreparation = runCatching {
  goalContinuationRecorder.reserveGoalReviewPass(run.request.workflowId, run.request.dbPathOverride)
}.fold(
  onSuccess = { reservation ->
    when (reservation) {
      GoalSubtaskReviewPassReservation.MissingState -> blockedGoalReviewRun(
        run,
        observability,
        "Goal-subtask review state is missing; review_base_sha must be captured before implementation " +
          "and cannot be substituted.",
      )
      is GoalSubtaskReviewPassCarryForward -> GoalReviewRunPreparation.CarryForward
      is GoalSubtaskReviewPassInFlight,
      is GoalSubtaskReviewPassReserved,
      -> buildGoalReviewRun(run, observability)
    }
  },
  onFailure = { error ->
    blockedGoalReviewRun(
      run,
      observability,
      goalReviewPreparationFailure("reservation", error),
      goalReviewPreparationDisposition(error),
    )
  },
)

internal fun FeatureTaskRuntimeRunLoop.buildGoalReviewRun(
  run: PhaseRun,
  observability: FeatureTaskRuntimeRunObservability,
): GoalReviewRunPreparation = runCatching {
  val resolved = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
  goalContinuationRecorder.buildGoalReviewInput(
    workflowId = run.request.workflowId,
    gitOperations = phaseGates.gitOperations,
    repoRoot = run.request.repoRoot,
    scope = FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope(
      dbOverride = run.request.dbPathOverride,
      scopedUntrackedExclusions = resolved?.let(::scopedReviewUntrackedExclusions),
      ownedPathspec = resolved?.workflowOwnedPaths.orEmpty(),
    ),
  )
}.fold(
  onSuccess = { prepared ->
    when (prepared) {
      GoalSubtaskReviewInputPreparation.MissingState -> {
        blockedGoalReviewRun(run, observability, "Goal-subtask review state disappeared before review launch.")
      }
      is GoalSubtaskReviewInputBlocked -> {
        blockedGoalReviewRun(run, observability, prepared.reason)
      }
      is GoalSubtaskReviewInputReady ->
        GoalReviewRunReady(run.copy(goalReviewInput = prepared.input))
    }
  },
  onFailure = { error ->
    blockedGoalReviewRun(
      run,
      observability,
      goalReviewPreparationFailure("input persistence", error),
      goalReviewPreparationDisposition(error),
    )
  },
)

internal fun FeatureTaskRuntimeRunLoop.goalReviewPreparationFailure(stage: String, error: Throwable): String {
  val location = error.stackTrace.firstOrNull { frame -> frame.className.startsWith("skillbill.") }
    ?.let { frame -> " at ${frame.className}.${frame.methodName}:${frame.lineNumber}" }
    .orEmpty()
  return "Goal-subtask review $stage failed$location: ${error.message.orEmpty()}"
}

internal fun FeatureTaskRuntimeRunLoop.goalReviewPreparationDisposition(
  error: Throwable,
): FeatureTaskRuntimeFailureDisposition = if ("[SQLITE_BUSY]" in error.message.orEmpty()) {
  FeatureTaskRuntimeFailureDisposition.RETRYABLE
} else {
  FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
}

internal fun FeatureTaskRuntimeRunLoop.blockedGoalReviewRun(
  run: PhaseRun,
  observability: FeatureTaskRuntimeRunObservability,
  reason: String,
  failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
): GoalReviewRunPreparation {
  blockAndPersist(
    BlockAndPersistArgs(
      run = run,
      attemptCount = 1,
      reason = reason,
      observability = observability,
      loopId = null,
      edgeIteration = null,
      failureDisposition = failureDisposition,
      payload = BlockAndPersistPayload(),
    ),
  )
  return GoalReviewRunPreparation.Blocked(reason, failureDisposition)
}

internal fun FeatureTaskRuntimeRunLoop.settleCarriedForwardGoalReview(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome {
  val acceptedOutput = loadCarriedForwardGoalReviewOutput(run).getOrElse { error ->
    return blockAndPersist(carriedForwardMissingReviewBlock(run, state, observability, error))
  }
  val normalizedOutput = acceptedOutput.normalizedOutput
  val iteration = state.nextIteration(run.phaseId)
  val phaseState = phaseStateRequest(
    PhaseStateRequestArgs(
      write = PhaseStateWriteArgs(
        run = run,
        iteration = iteration,
        status = STATUS_COMPLETED,
        finished = true,
        outputArtifact = normalizedOutput.canonicalJson,
      ),
      extras = PhaseStateRequestExtras(
        normalizedOutput = normalizedOutput,
        repairEvidence = acceptedOutput.repairEvidence,
      ),
    ),
  )
  state.reserveReviewPass(phaseState.reviewPassNumber)
  carriedForwardReviewPersistenceFailure(phaseState, run)?.let { failure ->
    return blockAndPersist(
      BlockAndPersistArgs(
        run = run,
        attemptCount = iteration,
        reason = failure,
        observability = observability,
        loopId = null,
        edgeIteration = null,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        payload = BlockAndPersistPayload(
          normalizedOutput = normalizedOutput,
          outputArtifact = normalizedOutput.canonicalJson,
          repairEvidence = acceptedOutput.repairEvidence,
        ),
      ),
    )
  }
  observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  return PhaseOutcome.completed(
    FeatureTaskRuntimePhaseOutput(
      run.phaseId,
      iteration,
      normalizedOutput.canonicalJson,
      normalizedOutput,
      acceptedOutput.repairEvidence,
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.carriedForwardReviewPersistenceFailure(
  phaseState: FeatureTaskRuntimePhaseStateRequest,
  run: PhaseRun,
): String? {
  val prefix = "Carried-forward goal review could not atomically persist its canonical result."
  return runCatching {
    recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
  }.fold(
    onSuccess = { persisted -> if (persisted) null else prefix },
    onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
  )
}
