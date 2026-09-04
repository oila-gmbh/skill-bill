package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.GoalSubtaskReviewInputBlocked
import skillbill.application.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.application.featuretask.model.GoalSubtaskReviewInputReady
import skillbill.application.featuretask.model.GoalSubtaskReviewPassCarryForward
import skillbill.application.featuretask.model.GoalSubtaskReviewPassInFlight
import skillbill.application.featuretask.model.GoalSubtaskReviewPassReservation
import skillbill.application.featuretask.model.GoalSubtaskReviewPassReserved
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

@Inject
class FeatureTaskRuntimeRunLoopPhaseRunnerMutatingPhase {
  internal fun reserveGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    runLoop.goalContinuationRecorder.reserveGoalReviewPass(run.request.workflowId, run.request.dbPathOverride)
  }.fold(
    onSuccess = { reservation ->
      when (reservation) {
        GoalSubtaskReviewPassReservation.MissingState -> blockedGoalReviewRun(
          runLoop,
          run,
          observability,
          "Goal-subtask review runLoop.state is missing; review_base_sha must be captured before implementation " +
            "and cannot be substituted.",
        )
        is GoalSubtaskReviewPassCarryForward -> GoalReviewRunPreparation.CarryForward
        is GoalSubtaskReviewPassInFlight,
        is GoalSubtaskReviewPassReserved,
        -> buildGoalReviewRun(runLoop, run, observability)
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        goalReviewPreparationFailure("reservation", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

  internal fun buildGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    val resolved = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    runLoop.goalContinuationRecorder.buildGoalReviewInput(
      workflowId = run.request.workflowId,
      gitOperations = runLoop.phaseGates.gitOperations,
      repoRoot = run.request.repoRoot,
      scope = FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope(
        dbOverride = run.request.dbPathOverride,
        scopedUntrackedExclusions = resolved?.let {
          runLoop.collaborators.phaseRunnerContinued1.scopedReviewUntrackedExclusions(runLoop, it)
        },
        ownedPathspec = resolved?.workflowOwnedPaths.orEmpty(),
      ),
    )
  }.fold(
    onSuccess = { prepared ->
      when (prepared) {
        GoalSubtaskReviewInputPreparation.MissingState -> {
          blockedGoalReviewRun(
            runLoop,
            run,
            observability,
            "Goal-subtask review runLoop.state disappeared before review launch.",
          )
        }
        is GoalSubtaskReviewInputBlocked -> {
          blockedGoalReviewRun(runLoop, run, observability, prepared.reason)
        }
        is GoalSubtaskReviewInputReady ->
          GoalReviewRunReady(run.copy(goalReviewInput = prepared.input))
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        goalReviewPreparationFailure("input persistence", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

  fun goalReviewPreparationFailure(stage: String, error: Throwable): String {
    val location = error.stackTrace.firstOrNull { frame -> frame.className.startsWith("skillbill.") }
      ?.let { frame -> " at ${frame.className}.${frame.methodName}:${frame.lineNumber}" }
      .orEmpty()
    return "Goal-subtask review $stage failed$location: ${error.message.orEmpty()}"
  }

  fun goalReviewPreparationDisposition(error: Throwable): FeatureTaskRuntimeFailureDisposition =
    if ("[SQLITE_BUSY]" in error.message.orEmpty()) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }

  internal fun blockedGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
    reason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ): GoalReviewRunPreparation {
    runLoop.collaborators.phaseAttemptsContinued2.blockAndPersist(
      runLoop,
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
}
