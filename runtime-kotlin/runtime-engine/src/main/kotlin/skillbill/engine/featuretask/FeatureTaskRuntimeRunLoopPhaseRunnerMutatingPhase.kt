package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.GoalSubtaskReviewInputBlocked
import skillbill.engine.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.GoalSubtaskReviewInputReady
import skillbill.engine.featuretask.model.GoalSubtaskReviewPassCarryForward
import skillbill.engine.featuretask.model.GoalSubtaskReviewPassInFlight
import skillbill.engine.featuretask.model.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.model.GoalSubtaskReviewPassReserved
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
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
    runLoop.goalContinuationRecorder.buildGoalReviewInput(
      workflowId = run.request.workflowId,
      gitOperations = runLoop.phaseGates.gitOperations,
      repoRoot = run.request.repoRoot,
      scope = FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope(
        dbOverride = run.request.dbPathOverride,
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
      val reconciliationError = error as? FeatureTaskRuntimeSubtaskCommitReconciliationError
        ?: FeatureTaskRuntimeSubtaskCommitReconciliationError(
          workflowId = run.request.workflowId,
          issueKey = run.request.issueKey,
          subtaskId = run.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
          reason = "Goal-subtask review input reads could not be reconciled (${error.message.orEmpty()}); " +
            "operator decision: repair the workflow store or checkpoint refs before resuming",
          cause = error,
        )
      runLoop.diagnostics.warning(
        "record_kind=refusal seam=FeatureTaskRuntimeRunLoopPhaseRunnerMutatingPhase.buildGoalReviewRun " +
          "value_used='review input' value_expected=durable committed review baseline " +
          "cause=${reconciliationError.reason}",
        reconciliationError,
      )
      blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        reconciliationError.message.orEmpty(),
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
