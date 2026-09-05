package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.featuretask.model.RemediationBaseBlocked
import skillbill.application.featuretask.model.RemediationBaseCoherent
import skillbill.error.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration

fun FeatureTaskRuntimeRunner.buildExecutePreparedRunTelemetryContext(
  runRequest: FeatureTaskRuntimeRunRequest,
  telemetrySessionId: String,
  reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>,
) = FeatureTaskRuntimeFinishedTelemetryContext(
  telemetrySessionId = telemetrySessionId,
  phaseOutcomes = {
    recorder.loadPhaseRecords(runRequest.workflowId, runRequest.dbPathOverride)
      .orEmpty()
      .mapValues { (_, record) -> record.status }
  },
  reviewFixIterationCount = { loadReviewFixIterationCount(runRequest) },
  auditGapIterationCount = { loadAuditGapIterationCount(runRequest) },
  auditRepairProgress = { loadAuditRepairProgress(runRequest) },
  regenerationTelemetry = { loadRegenerationTelemetry(runRequest) },
  findingVerificationTelemetry = { loadFindingVerificationTelemetry(runRequest) },
  dbOverride = runRequest.dbPathOverride,
  phaseTokenData = { serializeTokenData(phaseTokenAccumulator) },
  crashReconciliation = { reconciliation },
)

fun FeatureTaskRuntimeRunner.driveExecutePreparedRunLoop(
  runRequest: FeatureTaskRuntimeRunRequest,
  specSource: SpecSource,
  transitions: FeatureTaskRuntimeTransitionDeclaration,
  observability: FeatureTaskRuntimeRunObservability,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>,
): FeatureTaskRuntimeRunReport {
  reopenCappedReviewOnChangedDelta(runRequest)
  if (isGoalContinuationRun(runRequest)) {
    when (
      val remediation = goalContinuationRecorder.reconcileRemediationBaseCoherence(
        workflowId = runRequest.workflowId,
        gitOperations = phaseGates.gitOperations,
        repoRoot = runRequest.repoRoot,
        dbOverride = runRequest.dbPathOverride,
      )
    ) {
      is RemediationBaseBlocked ->
        return remediationBaseCoherenceBlockedReport(runRequest, remediation.operatorGuidance)
      is RemediationBaseCoherent -> Unit
    }
  }
  val state = FeatureTaskRuntimeRunState(
    recorder.loadPhaseRecords(runRequest.workflowId, runRequest.dbPathOverride).orEmpty(),
    transitions,
    recorder.loadPhaseLedger(runRequest.workflowId, runRequest.dbPathOverride).orEmpty(),
    outputValidator,
    recorder.reconcileReviewGeneration(runRequest.workflowId, runRequest.dbPathOverride),
  )
  val loop = FeatureTaskRuntimeRunLoop(
    recorder = recorder,
    goalContinuationRecorder = goalContinuationRecorder,
    outputValidator = outputValidator,
    phaseGates = phaseGates,
    subtaskLauncher = subtaskLauncher,
    phaseSettlementService = phaseSettlementService,
    activityStampWriter = activityStampWriter,
    clock = clock,
    context = FeatureTaskRuntimeRunLoopContext(
      runRequest,
      state,
      observability,
      specSource,
      transitions,
      phaseTokenAccumulator,
    ),
    diagnostics = diagnostics,
  )
  runRequest.operatorDecision?.let { decision ->
    loop.applyOperatorDecision(decision)?.let { rejection ->
      throw FeatureTaskRuntimeOperatorDecisionRejectedError(runRequest.workflowId, decision.wireValue, rejection)
    }
  }
  loop.drive()
  return loop.report()
}

fun FeatureTaskRuntimeRunner.finalizeExecutePreparedRunReport(
  runRequest: FeatureTaskRuntimeRunRequest,
  report: FeatureTaskRuntimeRunReport,
  specSource: SpecSource,
): FeatureTaskRuntimeRunReport {
  val terminalReport =
    persistGoalContinuationOutcome(goalContinuationRecorder, recorder, phaseGates.gitOperations, runRequest, report)
  phaseGates.specGate.finalizeSingleSpecOnTerminal(
    runRequest,
    terminalReport,
    specSource,
    { finalizingAgentId(runRequest) },
  )
  return terminalReport
}
