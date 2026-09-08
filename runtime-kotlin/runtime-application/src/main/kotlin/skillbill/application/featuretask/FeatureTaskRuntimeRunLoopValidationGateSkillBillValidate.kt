package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

@Inject
class FeatureTaskRuntimeRunLoopValidationGateSkillBillValidate {
  internal fun packBuildCommand(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
      return null
    }
    val validationChangedPaths = runLoop.collaborators.validationGateContinued2
      .validationChangedPaths(runLoop, run)
    return when (val resolution = runLoop.phaseGates.validationGateResolver.resolve(validationChangedPaths)) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

  internal fun runPhaseAttempts(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var iteration = state.nextIteration(run.phaseId)
    val continuationSegmentCount = runLoop.collaborators.phaseAttemptsContinued1
      .durableContinuationSegmentCount(runLoop, run)
    val nonOutputAttempts = runLoop.collaborators.phaseAttemptsContinued1.durableNonOutputAttempts(runLoop, run)
    prepareFixLoopState(runLoop, run, state, observability)?.let { return it }
    val semanticIteration = (
      state.fixLoopIterationFor(run.phaseId, iteration) - continuationSegmentCount - nonOutputAttempts.size
      ).coerceAtLeast(1)
    val crashResumed = state.resumedFromPriorProcess(run.phaseId)
    state.recordPhaseLaunched(run.phaseId)
    observability.started(
      run.phaseId,
      agentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry(
        resumed = iteration > 1 || state.hasPriorRecord(run.phaseId),
        startKind = featureTaskRuntimeStartContinuationKind(
          crashResumed = crashResumed,
          verifierReentry = run.reentry?.let {
            runLoop.collaborators.backwardEdge.isLoopDestination(
              runLoop,
              it,
            )
          } == true,
          attemptCount = iteration,
        ),
      ),
    )
    var outcome: PhaseOutcome? = null
    val loop = PhaseAttemptLoopState(
      iteration = iteration,
      malformedAttemptCount = 0,
      outputGateFailures = 0,
      semanticIteration = semanticIteration,
      continuationSegmentCount = continuationSegmentCount,
    )
    while (outcome == null) {
      outcome = resolveFixLoopOutcome(
        runLoop,
        FixLoopOutcomeArgs(
          context = phaseAttemptAccumulatorContext(
            run,
            state,
            loop.iteration,
            observability,
            runLoop.phaseTokenAccumulator,
          ),
          loop = loop,
          agentId = agentId,
        ),
      )
    }
    return outcome
  }

  /** Mutable per-phase fix-loop bookkeeping, held together so the branch handlers can advance it. */

  internal fun prepareFixLoopState(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val nonOutputAttempts = runLoop.collaborators.phaseAttemptsContinued1.durableNonOutputAttempts(runLoop, run)
    val processFailures = nonOutputAttempts.filterNot(FeatureTaskRuntimeNonOutputAttempt::paused)
    val operatorReopened = runLoop.collaborators.phaseAttemptsContinued1.operatorReopenedPhase(runLoop, run.phaseId)
    if (operatorReopened) state.restartAttemptBudget(run.phaseId)
    if (!operatorReopened) {
      FeatureTaskRuntimeAttemptBudgets
        .processFailureBlockReason(run.phaseId, processFailures.size, processFailures.lastOrNull()?.reason)
        ?.let { reason ->
          return runLoop.collaborators.phaseAttempts.blockInPhase(
            runLoop,
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

  internal fun resolveFixLoopOutcome(runLoop: FeatureTaskRuntimeRunLoop, args: FixLoopOutcomeArgs): PhaseOutcome? {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val loop = args.loop
    val agentId = args.agentId
    val attempt = runLoop.collaborators.recordRejection.attemptOnce(
      runLoop,
      recordRejectionAttemptArgs(
        PhaseAttemptContext(run, runLoop.state, loop.iteration, runLoop.observability),
        priorCorrection = loop.priorCorrection,
        phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
      ),
    )
    val context = FixLoopBranchContext(run, attempt, loop, runLoop.observability, agentId)
    val phaseAttempts = runLoop.collaborators.phaseAttempts
    return attempt.settledOutcome ?: when {
      attempt.incompleteWorkContinuationReason != null -> phaseAttempts.settleIncompleteWork(runLoop, context)
      attempt.boundaryBodyDeliveryContinuationReason != null ->
        phaseAttempts.settleBoundaryBodyDelivery(runLoop, context)
      attempt.malformedOutput -> phaseAttempts.settleMalformedOutput(runLoop, context)
      attempt.retryableTerminalRetryReason != null -> phaseAttempts.settleRetryableTerminal(runLoop, context)
      attempt.findingsOwedKind != null -> phaseAttempts.settleFindingsOwed(runLoop, context)
      else -> runLoop.collaborators.phaseAttemptsContinued1.settleSemanticFailure(runLoop, context)
    }
  }

  internal fun runDeclaredValidationGateCycle(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ): PhaseOutcome {
    val checkpoint = runLoop.collaborators.validationGateContinued4.resolveValidationGateCheckpoint(runLoop, run)
      ?: return PhaseOutcome.blocked(
        "Validation gate cycle could not resolve a repository checkpoint fingerprint.",
      )
    val iteration = state.nextIteration(run.phaseId)
    val context = phaseAttemptAccumulatorContext(run, state, iteration, observability, runLoop.phaseTokenAccumulator)
    val cycle = runLoop.validationGateCoordinator.execute(
      cycle = runLoop.collaborators.validationGateContinued4.validationGateCycleRequest(
        runLoop,
        ValidationGateCycleRequestArgs(context, checkpoint),
      ),
      onGateRunCount = { observability.validationGateProgress() },
    )
    return runLoop.collaborators.validationGateContinued4.settleValidationGateCycleResult(
      runLoop,
      SettleValidationGateCycleArgs(context, cycle),
    )
  }
}
