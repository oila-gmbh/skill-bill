package skillbill.application.featuretask

internal fun FeatureTaskRuntimeRunLoop.runDeclaredValidationGateCycle(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
): PhaseOutcome {
  val checkpoint = resolveValidationGateCheckpoint(run)
    ?: return PhaseOutcome.blocked(
      "Validation gate cycle could not resolve a repository checkpoint fingerprint.",
    )
  val iteration = state.nextIteration(run.phaseId)
  val context = phaseAttemptAccumulatorContext(run, state, iteration, observability, phaseTokenAccumulator)
  val cycle = validationGateCoordinator.execute(
    cycle = validationGateCycleRequest(ValidationGateCycleRequestArgs(context, checkpoint)),
    onGateRunCount = { observability.validationGateProgress() },
  )
  return settleValidationGateCycleResult(SettleValidationGateCycleArgs(context, cycle))
}
