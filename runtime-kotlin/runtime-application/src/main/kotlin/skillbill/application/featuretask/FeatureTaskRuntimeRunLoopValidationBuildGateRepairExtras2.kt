package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal fun FeatureTaskRuntimeRunLoop.prepareFixLoopState(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome? {
  val nonOutputAttempts = durableNonOutputAttempts(run)
  val processFailures = nonOutputAttempts.filterNot(FeatureTaskRuntimeNonOutputAttempt::paused)
  val operatorReopened = operatorReopenedPhase(run.phaseId)
  if (operatorReopened) state.restartAttemptBudget(run.phaseId)
  if (!operatorReopened) {
    FeatureTaskRuntimeAttemptBudgets
      .processFailureBlockReason(run.phaseId, processFailures.size, processFailures.lastOrNull()?.reason)
      ?.let { reason ->
        return blockInPhase(
          PhaseBlockRequest(
            run = run,
            attemptCount = state.nextIteration(run.phaseId),
            reason = reason,
            observability = observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          ),
        )
      }
  }
  return null
}

internal fun FeatureTaskRuntimeRunLoop.resolveFixLoopOutcome(args: FixLoopOutcomeArgs): PhaseOutcome? {
  val run = args.context.attempt.run
  val state = args.context.attempt.state
  val observability = args.context.attempt.observability
  val phaseTokenAccumulator = args.context.phaseTokenAccumulator
  val loop = args.loop
  val agentId = args.agentId
  val attempt = attemptOnce(
    recordRejectionAttemptArgs(
      PhaseAttemptContext(run, state, loop.iteration, observability),
      priorCorrection = loop.priorCorrection,
      phaseTokenAccumulator = phaseTokenAccumulator,
    ),
  )
  val context = FixLoopBranchContext(run, attempt, loop, observability, agentId)
  return attempt.settledOutcome ?: when {
    attempt.incompleteWorkContinuationReason != null -> settleIncompleteWork(context)
    attempt.boundaryBodyDeliveryContinuationReason != null -> settleBoundaryBodyDelivery(context)
    attempt.malformedOutput -> settleMalformedOutput(context)
    attempt.retryableTerminalRetryReason != null -> settleRetryableTerminal(context)
    attempt.findingsOwedKind != null -> settleFindingsOwed(context)
    else -> settleSemanticFailure(context)
  }
}
