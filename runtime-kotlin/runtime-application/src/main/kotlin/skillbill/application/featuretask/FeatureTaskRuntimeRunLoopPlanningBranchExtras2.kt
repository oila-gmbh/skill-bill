package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration

internal fun FeatureTaskRuntimeRunLoop.phaseDeclarationForRun(
  phaseId: String,
  state: FeatureTaskRuntimeRunState,
  reentry: PendingReentry?,
): FeatureTaskRuntimePhaseDeclaration {
  val declaration = phaseDeclaration(
    phaseId,
    request.runInvariants.featureSize,
    qualityGateSelection(),
  )
  return when {
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID ->
      declaration.copy(
        projectionDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.auditRemediationProjections(),
      )
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
      state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) > 0 ->
      declaration.copy(
        projectionDeclarations = declaration.projectionDeclarations +
          FeatureTaskRuntimePhaseWorkflowDefinition.priorGapMemoryDeclaration(phaseId),
      )
    else -> declaration
  }
}

internal fun FeatureTaskRuntimeRunLoop.buildPhaseRun(
  phaseId: String,
  request: FeatureTaskRuntimeRunRequest,
  declaration: FeatureTaskRuntimePhaseDeclaration,
  specSource: SpecSource,
  reentry: PendingReentry?,
): PhaseRun {
  val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = phaseId,
    assignment = request.agentAssignment,
    invokedAgentId = request.invokedAgentId,
  )
  return PhaseRun(
    phaseId = phaseId,
    declaration = declaration,
    resolvedAgent = resolvedAgent,
    modelDirective = FeatureTaskRuntimeModelResolver.resolve(
      phaseId,
      resolvedAgent.resolvedAgentId,
      request.modelAssignment,
    ),
    compaction = request.compactionSettings.directiveFor(phaseId),
    request = request,
    specSource = specSource,
    reentry = reentry,
  )
}

internal fun FeatureTaskRuntimeRunLoop.runPreparedPhase(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
): PhaseOutcome = when (val prepared = prepareGoalReviewRun(run, observability)) {
  is GoalReviewRunReady -> when {
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
      runDeclaredReviewDriverCycle(prepared.run, state, observability)
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
      runDeclaredValidationGateCycle(prepared.run, state, observability, phaseTokenAccumulator)
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
      runDeclaredBuildGateCycle(prepared.run, state, observability, phaseTokenAccumulator)
    else -> runPhaseAttempts(prepared.run, state, observability, phaseTokenAccumulator)
  }
  GoalReviewRunPreparation.CarryForward -> settleCarriedForwardGoalReview(
    run = run,
    state = state,
    observability = observability,
  )
  is GoalReviewRunPreparation.Blocked -> PhaseOutcome.blocked(prepared.reason)
}
