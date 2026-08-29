package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.loadCarriedForwardGoalReviewOutput(run: PhaseRun) = runCatching {
  val output = goalContinuationRecorder.lastGoalReviewResult(run.request.workflowId, run.request.dbPathOverride)
    ?: throw MissingCarriedForwardGoalReviewResultException()
  outputValidator.validatePhaseOutput(output, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
}

internal fun FeatureTaskRuntimeRunLoop.carriedForwardMissingReviewBlock(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
  error: Throwable,
): BlockAndPersistArgs {
  val detail = if (error is MissingCarriedForwardGoalReviewResultException) {
    "missing."
  } else {
    "malformed: ${error.message.orEmpty()}"
  }
  return BlockAndPersistArgs(
    run = run,
    attemptCount = state.nextIteration(run.phaseId),
    reason = "Goal-subtask review pass budget is exhausted but its durable raw " +
      "review result is $detail",
    observability = observability,
    loopId = null,
    edgeIteration = null,
    failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    payload = BlockAndPersistPayload(),
  )
}
