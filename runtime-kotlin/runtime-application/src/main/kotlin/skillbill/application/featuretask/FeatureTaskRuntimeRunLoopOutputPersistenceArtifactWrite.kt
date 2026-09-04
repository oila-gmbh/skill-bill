package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence

internal fun FeatureTaskRuntimeRunLoopOutputPersistence.prepareLaunch(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: PrepareLaunchArgs,
): PreparedLaunch {
  val run = args.run
  val state = args.state
  val priorCorrection = args.priorCorrection
  val durablyClosedCriterionRefs = args.durablyClosedCriterionRefs
  val repositoryCheckpoint = args.repositoryCheckpoint
  val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
  val handoff = assembleLaunchHandoff(
    runLoop,
    AssembleLaunchHandoffArgs(run, state, durablyClosedCriterionRefs, repositoryCheckpoint, resolvedBranchRecord),
  )
  runLoop.recorder.validateHandoffDeclarations(handoff.projectionDeclarations)
  val sharedEvidence = runLoop.collaborators.outputVerificationContinued1.resolveSharedReviewEvidence(
    runLoop,
    run,
    repositoryCheckpoint,
  )
  val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    handoff,
    run.request.workflowId,
    runLoop.planningProjectionValidator,
    run.request.agentAddonSelection,
    sharedEvidence?.reference,
  )
  runLoop.recorder.recordPhaseBriefing(
    run.request.workflowId,
    briefing,
    run.request.dbPathOverride,
    sharedEvidence?.measurement,
  )
  val prompt = composeLaunchPrompt(
    runLoop,
    ComposeLaunchPromptArgs(run, state, handoff, priorCorrection, briefing),
  )
  return PreparedLaunch(briefing, prompt)
}

private fun FeatureTaskRuntimeRunLoopOutputPersistence.assembleLaunchHandoff(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: AssembleLaunchHandoffArgs,
) = FeatureTaskRuntimeHandoffContract.assembleHandoff(
  FeatureTaskRuntimeHandoffAssemblyRequest(
    declaration = args.run.declaration,
    runInvariants = args.run.request.runInvariants,
    recordedOutputs = args.state.outputs(),
    drivingVerdict = args.run.reentry?.drivingVerdict,
    reentryGapCriteria = emptyList(),
    priorGapMemory = runLoop.collaborators.launchContinued2.priorGapMemoryFor(runLoop, args.run, args.state),
    durablyClosedCriterionRefs = args.durablyClosedCriterionRefs,
    repairLedger = null,
    repositoryCheckpoint = args.repositoryCheckpoint,
    expectedRepositoryCheckpoint = expectedCheckpointForLaunch(args.run, args.repositoryCheckpoint)
      ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
    branchIdentity = args.resolvedBranchRecord?.branch,
    baseBranch = args.resolvedBranchRecord?.baseBranch ?: "main",
    validationDepth = args.run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
    qualityGateSelection = runLoop.collaborators.transitions.qualityGateSelection(runLoop),
  ),
).copy(
  recordedFindingVerdicts = runLoop.collaborators.outputVerification.recordedFindingVerdictsForFixHandoff(
    runLoop,
    args.run,
    args.state,
  ),
)

private fun expectedCheckpointForLaunch(
  run: PhaseRun,
  repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
): String? = if (
  run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
  run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
) {
  repositoryCheckpoint?.fingerprint
} else {
  run.reentry?.expectedRepositoryCheckpoint ?: repositoryCheckpoint?.fingerprint
}

private fun FeatureTaskRuntimeRunLoopOutputPersistence.composeLaunchPrompt(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: ComposeLaunchPromptArgs,
): String {
  val run = args.run
  val state = args.state
  val handoff = args.handoff
  val priorCorrection = args.priorCorrection
  val briefing = args.briefing
  val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
  val passNumber = reviewPassNumber(runLoop, run, state)
  val depthResolution = passNumber?.let { pass ->
    FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, pass)
  }
  val executedTier = RuntimeOwnedReviewMode.execute(
    depthResolution?.resolvedTier ?: run.request.runInvariants.codeReviewMode,
  )
  depthResolution?.let { resolution ->
    runLoop.collaborators.planningBranch.persistResolvedReviewTier(runLoop, run, resolution)
  }
  return FeatureTaskRuntimePhasePromptComposer.compose(
    FeatureTaskRuntimePhasePromptComposeInputs(
      issueKey = run.request.issueKey,
      briefing = briefing,
      suppressDecomposition = isGoalContinuationRun(run.request),
      codeReviewMode = executedTier,
      reviewPassNumber = passNumber,
      goalSubtaskReviewInput = run.goalReviewInput,
      baselineUntrackedPaths = resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
      resolvedReviewTier = depthResolution?.let { executedTier },
      reviewDecidingRule = depthResolution?.decidingRule,
      repairLedger = handoff.repairLedger,
      priorReviewContext = null,
      priorSchemaFailure = priorCorrection?.schemaGateReason,
      priorTerminalFailure = priorCorrection?.retryableTerminalReason,
      priorFindingCoverage = priorCorrection?.findingCoverageReason,
      correctiveRepairContext = priorCorrection?.correctiveRepairContext,
      operatorBlockRetry = runLoop.session.operatorBlockRetry
        ?.takeIf { it.phaseId == run.phaseId && !runLoop.session.operatorBlockRetryCompleted },
      implementationContinuation = runLoop.collaborators.outputVerification.implementationContinuationFor(runLoop, run),
      validationGateFindings = run.validationGateFindings,
      validationGateTriagePlan = run.validationGateTriagePlan,
      validationGateRepair = run.validationGateRepair,
      validationGateTriage = run.validationGateTriage,
      agentRunValidateFallback = run.agentRunValidateFallback,
      packCollectAllCommand = runLoop.collaborators.validationGateContinued2.packCollectAllCommand(runLoop, run),
      packBuildCommand = runLoop.collaborators.validationGateContinued3.packBuildCommand(runLoop, run),
    ),
  ) + runLoop.collaborators.launch.verifyFindingsSpecIntentSection(runLoop, run)
}
