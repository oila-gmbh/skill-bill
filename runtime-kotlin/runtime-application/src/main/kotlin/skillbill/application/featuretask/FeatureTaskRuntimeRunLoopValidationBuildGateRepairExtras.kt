package skillbill.application.featuretask

import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun FeatureTaskRuntimeRunLoop.validationChangedPaths(run: PhaseRun): List<String> =
  resolveRepositoryCheckpoint(run)?.workingTreeOwnedPaths.orEmpty().distinct().sorted()

internal fun FeatureTaskRuntimeRunLoop.packCollectAllCommand(run: PhaseRun): String? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
    return null
  }
  return when (val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths(run))) {
    is ValidationGateResolution.Declared -> resolution.declaration.collectAllFullGateCommand.joinToString(" ")
    is ValidationGateResolution.Absent -> null
    is ValidationGateResolution.Incompatible -> null
  }
}

internal fun FeatureTaskRuntimeRunLoop.packBuildCommand(run: PhaseRun): String? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
    return null
  }
  return when (val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths(run))) {
    is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
    is ValidationGateResolution.Absent -> null
    is ValidationGateResolution.Incompatible -> null
  }
}

internal fun FeatureTaskRuntimeRunLoop.runPhaseAttempts(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
): PhaseOutcome {
  val agentId = run.resolvedAgent.resolvedAgentId
  var iteration = state.nextIteration(run.phaseId)
  val continuationSegmentCount = durableContinuationSegmentCount(run)
  val nonOutputAttempts = durableNonOutputAttempts(run)
  prepareFixLoopState(run, state, observability)?.let { return it }
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
        verifierReentry = run.reentry?.let { isLoopDestination(it) } == true,
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
      FixLoopOutcomeArgs(
        context = phaseAttemptAccumulatorContext(run, state, loop.iteration, observability, phaseTokenAccumulator),
        loop = loop,
        agentId = agentId,
      ),
    )
  }
  return outcome
}

/** Mutable per-phase fix-loop bookkeeping, held together so the branch handlers can advance it. */
