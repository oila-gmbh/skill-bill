package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

internal fun FeatureTaskRuntimeRunLoop.goalReviewPhaseCompletionRequest(
  args: PhaseReviewPersistenceArgs,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
): GoalReviewPhaseCompletionRequest {
  val outputText = normalizedOutput.canonicalJson
  val outputMap = normalizedOutput.envelope
  val recordedVerdicts = recorder.recordedFindingVerdicts(outputMap, request.dbPathOverride)
  val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
  val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
  return GoalReviewPhaseCompletionRequest(
    phaseState = phaseStateRequest(
      PhaseStateRequestArgs(
        write = PhaseStateWriteArgs(
          run = args.run,
          iteration = args.iteration,
          status = STATUS_COMPLETED,
          finished = true,
          outputArtifact = outputText,
        ),
        extras = PhaseStateRequestExtras(
          fileManifest = args.fileManifest,
          normalizedOutput = normalizedOutput,
          repairEvidence = repairEvidence,
        ),
      ),
    ),
    verdict = outcome.verdict,
    unresolvedFindingCount = outcome.unresolvedFindingCount,
    findings = findings,
    rawReviewResult = outputText,
    blockerDispositions = GoalSubtaskReviewSummaryReducer.blockerDispositions(
      outputMap,
      priorBlockerFindingIds(),
    ),
    commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
  )
}

internal fun FeatureTaskRuntimeRunLoop.prepareLaunch(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  priorCorrection: PriorAttemptCorrection?,
  durablyClosedCriterionRefs: List<String>,
  repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
): PreparedLaunch {
  val resolvedBranchRecord = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
  val handoff = assembleLaunchHandoff(
    run,
    state,
    durablyClosedCriterionRefs,
    repositoryCheckpoint,
    resolvedBranchRecord,
  )
  recorder.validateHandoffDeclarations(handoff.projectionDeclarations)
  val sharedEvidence = resolveSharedReviewEvidence(run, repositoryCheckpoint)
  val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    handoff,
    run.request.workflowId,
    planningProjectionValidator,
    run.request.agentAddonSelection,
    sharedEvidence?.reference,
  )
  recorder.recordPhaseBriefing(
    run.request.workflowId,
    briefing,
    run.request.dbPathOverride,
    sharedEvidence?.measurement,
  )
  val prompt = composeLaunchPrompt(run, state, handoff, priorCorrection, briefing)
  return PreparedLaunch(briefing, prompt)
}

private fun FeatureTaskRuntimeRunLoop.assembleLaunchHandoff(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  durablyClosedCriterionRefs: List<String>,
  repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  resolvedBranchRecord: FeatureTaskRuntimeResolvedBranch?,
) = FeatureTaskRuntimeHandoffContract.assembleHandoff(
  declaration = run.declaration,
  runInvariants = run.request.runInvariants,
  recordedOutputs = state.outputs(),
  drivingVerdict = run.reentry?.drivingVerdict,
  reentryGapCriteria = emptyList(),
  priorGapMemory = priorGapMemoryFor(run, state),
  durablyClosedCriterionRefs = durablyClosedCriterionRefs,
  repairLedger = null,
  repositoryCheckpoint = repositoryCheckpoint,
  expectedRepositoryCheckpoint = expectedCheckpointForLaunch(run, repositoryCheckpoint)
    ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
  branchIdentity = resolvedBranchRecord?.branch,
  baseBranch = resolvedBranchRecord?.baseBranch ?: "main",
  validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
  qualityGateSelection = qualityGateSelection(),
).copy(recordedFindingVerdicts = recordedFindingVerdictsForFixHandoff(run, state))

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

private fun FeatureTaskRuntimeRunLoop.composeLaunchPrompt(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  handoff: FeatureTaskRuntimePhaseHandoff,
  priorCorrection: PriorAttemptCorrection?,
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
): String {
  val resolvedBranchRecord = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
  val passNumber = reviewPassNumber(run, state)
  val depthResolution = passNumber?.let { pass ->
    FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, pass)
  }
  val executedTier = RuntimeOwnedReviewMode.execute(
    depthResolution?.resolvedTier ?: run.request.runInvariants.codeReviewMode,
  )
  depthResolution?.let { resolution -> persistResolvedReviewTier(run, resolution) }
  return FeatureTaskRuntimePhasePromptComposer.compose(
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
    operatorBlockRetry = operatorBlockRetry
      ?.takeIf { it.phaseId == run.phaseId && !operatorBlockRetryCompleted },
    implementationContinuation = implementationContinuationFor(run),
    validationGateFindings = run.validationGateFindings,
    validationGateTriagePlan = run.validationGateTriagePlan,
    validationGateRepair = run.validationGateRepair,
    validationGateTriage = run.validationGateTriage,
    agentRunValidateFallback = run.agentRunValidateFallback,
    packCollectAllCommand = packCollectAllCommand(run),
    packBuildCommand = packBuildCommand(run),
  ) + verifyFindingsSpecIntentSection(run)
}
