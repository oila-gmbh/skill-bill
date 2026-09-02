package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.GoalReviewPhaseCompletionRequest
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.install.model.InstallAgent
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

internal fun FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: PersistPhaseArgs,
) {
  val write = args.write
  val phaseState =
    phaseStateRequest(
      runLoop,
      PhaseStateRequestArgs(
        write = write,
        extras = PhaseStateRequestExtras(
          fileManifest = args.fileManifest,
          launched = args.launched,
          reviewRunId = args.reviewRunId,
        ),
      ),
    )
  runLoop.state.reserveReviewPass(phaseState.reviewPassNumber)
  runLoop.recorder.recordPhaseState(
    phaseState,
    write.run.request.dbPathOverride,
  )
}

internal fun FeatureTaskRuntimeRunLoopOutputPersistence.phaseStateRequest(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: PhaseStateRequestArgs,
): FeatureTaskRuntimePhaseStateRequest {
  val write = args.write
  val run = write.run
  val extras = args.extras
  val fileManifest = extras.fileManifest
  return FeatureTaskRuntimePhaseStateRequest(
    workflowId = run.request.workflowId,
    phaseId = run.phaseId,
    status = write.status,
    attemptCount = write.iteration,
    resolvedAgentId = run.resolvedAgent.resolvedAgentId,
    finished = write.finished,
    outputArtifact = write.outputArtifact,
    normalizedOutput = extras.normalizedOutput,
    repairEvidence = extras.repairEvidence,
    repositoryFingerprint = extras.repositoryFingerprint,
    fileManifestBefore = fileManifest?.before.orEmpty(),
    fileManifestAfter = fileManifest?.after.orEmpty(),
    fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
    loopId = run.reentry?.loopId,
    edgeIteration = run.reentry?.edgeIteration,
    reviewPassNumber = reviewPassNumber(runLoop, run, runLoop.state),
    auditScopeCriterionRefs = if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      runLoop.collaborators.phaseRunner.openAuditCriterionRefs(runLoop)
    } else {
      emptyList()
    },
    launchedModel = extras.launched?.modelOverride,
    launchedEffort = extras.launched?.persistedEffort,
    launchOutcomeKnown = extras.launched != null,
    reviewRunId = extras.reviewRunId,
  )
}

/**
 * The model/effort the child is actually launched with. Cursor takes model and effort merged into
 * one bracketed `--model` argument, so its [persistedEffort] is null: the merged model already
 * carries the effort, and recording it twice would let the two drift apart.
 */
internal fun FeatureTaskRuntimeRunLoopOutputPersistence.launchedModelDirective(run: PhaseRun): LaunchedModelDirective {
  val model = run.modelDirective?.model
  val effort = run.modelDirective?.effort
  if (run.resolvedAgent.resolvedAgentId == InstallAgent.CURSOR.id && model != null && effort != null) {
    return LaunchedModelDirective("$model[effort=$effort]", effort, persistedEffort = null)
  }
  return LaunchedModelDirective(model, effort, effort)
}

internal fun FeatureTaskRuntimeRunLoopOutputPersistence.reviewPassNumber(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
): Int? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
  val durable = runLoop.collaborators.planningBranch.goalReviewStateOrNull(runLoop) ?: return 1
  return resolveReviewPassNumber(
    reservedPassNumber = durable.reservedPassNumber ?: state.currentReviewPassNumber(),
    completedReviewPassCount = durable.completedPassCount,
  )
}

internal fun FeatureTaskRuntimeRunLoopOutputPersistence.goalReviewPhaseCompletionRequest(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: PhaseReviewPersistenceArgs,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
): GoalReviewPhaseCompletionRequest {
  val outputText = normalizedOutput.canonicalJson
  val outputMap = normalizedOutput.envelope
  val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(outputMap, runLoop.request.dbPathOverride)
  val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
  val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
  return GoalReviewPhaseCompletionRequest(
    phaseState = phaseStateRequest(
      runLoop,
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
      runLoop.collaborators.planningBranch.priorBlockerFindingIds(runLoop),
    ),
    commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
  )
}
