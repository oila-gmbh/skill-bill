package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.model.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.RemediationBaseBlocked
import skillbill.engine.featuretask.model.RemediationBaseCoherent
import skillbill.engine.featuretask.validation.durableValidationChangedPaths
import skillbill.engine.featuretask.validation.resolveRequiredValidationCommand
import skillbill.error.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import java.util.concurrent.CancellationException

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
      .mapValues { (_, record) -> record.status.wireValue }
  },
  reviewFixIterationCount = { loadReviewFixIterationCount(runRequest) },
  auditGapIterationCount = { loadAuditGapIterationCount(runRequest) },
  auditRepairProgress = { loadAuditRepairProgress(runRequest) },
  auditRepairCycle = { auditCycleForTelemetry(runRequest) },
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
  val state = createExecutePreparedRunState(runRequest, transitions)
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

private fun FeatureTaskRuntimeRunner.createExecutePreparedRunState(
  runRequest: FeatureTaskRuntimeRunRequest,
  transitions: FeatureTaskRuntimeTransitionDeclaration,
): FeatureTaskRuntimeRunState = FeatureTaskRuntimeRunState(
  initialRecords = recorder.loadPhaseRecords(runRequest.workflowId).orEmpty(),
  transitions = transitions,
  initialLedger = recorder.loadPhaseLedger(runRequest.workflowId).orEmpty(),
  outputValidator = outputValidator,
  initialReviewGeneration = recorder.reconcileReviewGeneration(runRequest.workflowId),
  validationEvidenceCommandResolver = { validationEvidence ->
    resolveRequiredValidationCommand(
      resolver = phaseGates.validationGateResolver,
      requiredCommandForDeclaration = { declaration ->
        phaseGates.validationGateCoordinator.requiredValidationCommand(
          runRequest.repoRoot,
          runRequest.workflowId,
          declaration,
        )
      },
      changedPaths = durableValidationChangedPaths(recorder, runRequest.workflowId),
      evidence = validationEvidence,
      sourceLabel = "validate",
    )
  },
)

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

private fun FeatureTaskRuntimeRunner.auditCycleForTelemetry(request: FeatureTaskRuntimeRunRequest): AuditRepairCycle? =
  runCatching {
    phaseSettlementService.auditRepairCycle(
      request.workflowId,
      recorder.loadPhaseRecords(request.workflowId)
        ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)?.attemptCount ?: 1,
    )
  }.getOrElse { error ->
    if (error is CancellationException) throw error
    diagnostics.warning("Audit completion telemetry could not read durable cycle evidence.", error)
    null
  }
