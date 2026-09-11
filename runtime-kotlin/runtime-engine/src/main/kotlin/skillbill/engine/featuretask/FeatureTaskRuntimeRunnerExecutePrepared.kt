package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.model.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.RemediationBaseBlocked
import skillbill.engine.featuretask.model.RemediationBaseCoherent
import skillbill.error.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
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
    recorder.loadPhaseRecords(runRequest.workflowId)
      .orEmpty()
      .mapValues { (_, record) -> record.status }
  },
  reviewFixIterationCount = { loadReviewFixIterationCount(runRequest) },
  auditGapIterationCount = { loadAuditGapIterationCount(runRequest) },
  auditRepairProgress = { loadAuditRepairProgress(runRequest) },
  regenerationTelemetry = { loadRegenerationTelemetry(runRequest) },
  findingVerificationTelemetry = { loadFindingVerificationTelemetry(runRequest) },
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
  invalidateStaleGoalReviewApproval(runRequest)
  reopenCappedReviewOnChangedDelta(runRequest)
  if (isGoalContinuationRun(runRequest)) {
    when (
      val remediation = goalContinuationRecorder.reconcileRemediationBaseCoherence(
        workflowId = runRequest.workflowId,
        gitOperations = phaseGates.gitOperations,
        repoRoot = runRequest.repoRoot,
      )
    ) {
      is RemediationBaseBlocked ->
        return remediationBaseCoherenceBlockedReport(runRequest, remediation.operatorGuidance)
      is RemediationBaseCoherent -> Unit
    }
  }
  val state = FeatureTaskRuntimeRunState(
    recorder.loadPhaseRecords(runRequest.workflowId).orEmpty(),
    transitions,
    recorder.loadPhaseLedger(runRequest.workflowId).orEmpty(),
    outputValidator,
    recorder.reconcileReviewGeneration(runRequest.workflowId),
  )
  val loop = FeatureTaskRuntimeRunLoop(
    FeatureTaskRuntimeRunLoopDependencies(
      recorder = recorder,
      goalContinuationRecorder = goalContinuationRecorder,
      outputValidator = outputValidator,
      phaseGates = phaseGates,
      subtaskLauncher = subtaskLauncher,
      phaseSettlementService = phaseSettlementService,
      activityStampWriter = activityStampWriter,
      clock = dependencies.clock,
      collaborators = runLoopCollaborators,
    ),
    FeatureTaskRuntimeRunLoopContext(
      runRequest,
      state,
      observability,
      specSource,
      transitions,
      phaseTokenAccumulator,
    ),
    runnerDiagnostics,
  )
  runRequest.operatorDecision?.let { decision ->
    loop.applyOperatorDecision(decision)?.let { rejection ->
      throw FeatureTaskRuntimeOperatorDecisionRejectedError(runRequest.workflowId, decision.wireValue, rejection)
    }
  }
  loop.drive()
  return loop.report()
}

private fun FeatureTaskRuntimeRunner.invalidateStaleGoalReviewApproval(request: FeatureTaskRuntimeRunRequest) {
  if (isGoalContinuationRun(request)) invalidateStaleGoalReviewApprovalForGoal(request)
}

private fun FeatureTaskRuntimeRunner.invalidateStaleGoalReviewApprovalForGoal(request: FeatureTaskRuntimeRunRequest) {
  invalidateStaleGoalReviewApprovalForGoalRuntime(this, request)
}

internal fun FeatureTaskRuntimeRunner.staleApprovalReconciliationFailure(
  request: FeatureTaskRuntimeRunRequest,
  reason: String,
  cause: Throwable?,
): FeatureTaskRuntimeSubtaskCommitReconciliationError {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = request.workflowId,
    issueKey = request.issueKey,
    subtaskId = request.goalContinuation?.subtaskId?.toString() ?: "unknown",
    reason = reason,
    cause = cause,
  )
  runnerDiagnostics.warning(
    "record_kind=refusal seam=FeatureTaskRuntimeRunner.invalidateStaleGoalReviewApproval " +
      "value_used='${request.workflowId}' value_expected=durable stale-approval evidence cause=${error.reason}",
    error,
  )
  return error
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
