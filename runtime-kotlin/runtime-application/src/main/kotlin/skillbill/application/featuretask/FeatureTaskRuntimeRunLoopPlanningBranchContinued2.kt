package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration

fun FeatureTaskRuntimeRunLoopPlanningBranch.regenerationCapExhaustionReason(
  runLoop: FeatureTaskRuntimeRunLoop,
  loopId: String,
  edgeIteration: Int,
): String {
  val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.entries
    .firstOrNull { it.value == loopId }?.key
  val latest = producer?.let { producing ->
    runLoop.recorder.loadQuarantinedRecords(runLoop.request.workflowId, runLoop.request.dbPathOverride)
      .orEmpty()
      .lastOrNull { it.producingPhaseId == producing }
  }
  val recordId = latest?.recordIdentifier() ?: producer?.let { "$it#<unknown-iteration>" } ?: "<unknown>"
  return "Quarantine-and-regenerate loop '$loopId' exhausted its regeneration cap after $edgeIteration " +
    "attempt(s): the quarantined record '$recordId' produced by phase '${producer ?: "<unknown>"}' still " +
    "fails projection validation. The run blocks durably rather than regenerating past the cap; recover the " +
    "record out of band by deleting or migrating the offending row."
}

internal fun FeatureTaskRuntimeRunLoopPlanningBranch.runPhase(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: RunPhaseArgs,
): PhaseOutcome {
  val phaseId = args.phaseId
  val request = args.request
  val state = args.state
  val observability = args.observability
  val specSource = args.specSource
  val reentry = args.reentry
  val phaseTokenAccumulator = args.phaseTokenAccumulator
  val declaration = phaseDeclarationForRun(runLoop, phaseId, runLoop.state, reentry)
  val run = buildPhaseRun(
    runLoop,
    BuildPhaseRunArgs(phaseId, runLoop.request, declaration, runLoop.specSource, reentry),
  )
  runLoop.collaborators.phaseRunner.preLaunchBlock(
    runLoop,
    run,
    runLoop.state,
    runLoop.observability,
  )?.let { return it }
  return runPreparedPhase(runLoop, run, runLoop.state, runLoop.observability, runLoop.phaseTokenAccumulator)
}

internal fun FeatureTaskRuntimeRunLoopPlanningBranch.phaseDeclarationForRun(
  runLoop: FeatureTaskRuntimeRunLoop,
  phaseId: String,
  state: FeatureTaskRuntimeRunState,
  reentry: PendingReentry?,
): FeatureTaskRuntimePhaseDeclaration {
  val declaration = phaseDeclaration(
    phaseId,
    runLoop.request.runInvariants.featureSize,
    runLoop.collaborators.transitions.qualityGateSelection(runLoop),
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

internal fun FeatureTaskRuntimeRunLoopPlanningBranch.buildPhaseRun(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: BuildPhaseRunArgs,
): PhaseRun {
  val phaseId = args.phaseId
  val declaration = args.declaration
  val reentry = args.reentry
  val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = phaseId,
    assignment = runLoop.request.agentAssignment,
    invokedAgentId = runLoop.request.invokedAgentId,
  )
  return PhaseRun(
    phaseId = phaseId,
    declaration = declaration,
    resolvedAgent = resolvedAgent,
    modelDirective = FeatureTaskRuntimeModelResolver.resolve(
      phaseId,
      resolvedAgent.resolvedAgentId,
      runLoop.request.modelAssignment,
    ),
    compaction = runLoop.request.compactionSettings.directiveFor(phaseId),
    request = runLoop.request,
    specSource = runLoop.specSource,
    reentry = reentry,
  )
}

internal fun FeatureTaskRuntimeRunLoopPlanningBranch.runPreparedPhase(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
): PhaseOutcome = when (
  val prepared = runLoop.collaborators.phaseRunnerContinued1.prepareGoalReviewRun(
    runLoop,
    run,
    observability,
  )
) {
  is GoalReviewRunReady -> when {
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
      runLoop.collaborators.phaseRunner.runDeclaredReviewDriverCycle(runLoop, prepared.run, state, observability)
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
      runLoop.collaborators.validationGateContinued3.runDeclaredValidationGateCycle(
        runLoop,
        prepared.run,
        state,
        observability,
        runLoop.phaseTokenAccumulator,
      )
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
      runLoop.collaborators.validationGate.runDeclaredBuildGateCycle(
        runLoop,
        prepared.run,
        state,
        observability,
        runLoop.phaseTokenAccumulator,
      )
    else -> runLoop.collaborators.validationGateContinued3.runPhaseAttempts(
      runLoop,
      prepared.run,
      state,
      observability,
      runLoop.phaseTokenAccumulator,
    )
  }
  GoalReviewRunPreparation.CarryForward ->
    runLoop.collaborators.phaseRunnerContinued3.settleCarriedForwardGoalReview(
      runLoop,
      run = run,
      state = state,
      observability = observability,
    )
  is GoalReviewRunPreparation.Blocked -> PhaseOutcome.blocked(prepared.reason)
}
