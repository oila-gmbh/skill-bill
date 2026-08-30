package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.telemetry.model.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.application.telemetry.model.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

internal fun FeatureTaskRuntimeRunner.executePreparedRun(
  runRequest: FeatureTaskRuntimeRunRequest,
  reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
): FeatureTaskRuntimeRunReport {
  val specSource = specSourceResolver.resolve(
    repoRoot = runRequest.repoRoot,
    specReference = runRequest.runInvariants.specReference,
    isGoalContinuation = isGoalContinuationRun(runRequest),
  )
  emitFeatureTaskRuntimeEventSafely(
    diagnostics = runnerDiagnostics,
    seam = "RunStarted event-sink emission",
  ) {
    runRequest.eventSink.emit(
      FeatureTaskRuntimeRunEvent.RunStarted(runRequest.workflowId, runRequest.runInvariants.featureSize.name),
    )
  }
  val telemetrySessionId = lifecycleTelemetry.started(runRequest)
  val observability = FeatureTaskRuntimeRunObservability(recorder, runRequest, runnerDiagnostics)
  val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
  val telemetryContext = buildExecutePreparedRunTelemetryContext(
    runRequest,
    telemetrySessionId,
    reconciliation,
    phaseTokenAccumulator,
  )
  val transitions = transitionsFor(runRequest)
  val report = runCatching {
    driveExecutePreparedRunLoop(runRequest, specSource, transitions, observability, phaseTokenAccumulator)
  }.onFailure {
    lifecycleTelemetry.finishedError(
      telemetryContext.copy(phaseTokenData = { serializeTokenData(phaseTokenAccumulator) }),
    )
  }.getOrThrow()
  val terminalReport = finalizeExecutePreparedRunReport(runRequest, report, specSource)
  lifecycleTelemetry.finished(terminalReport, telemetryContext)
  return terminalReport
}

internal fun FeatureTaskRuntimeRunner.reopenCappedReviewOnChangedDelta(request: FeatureTaskRuntimeRunRequest) {
  if (!cappedReviewIsStale(request)) return
  checkNotNull(recorder.persistReviewGenerationInvalidation(request.workflowId, request.dbPathOverride)) {
    "Could not durably reopen the stale capped review for workflow '${request.workflowId}'."
  }
}

internal fun FeatureTaskRuntimeRunner.cappedReviewIsStale(request: FeatureTaskRuntimeRunRequest): Boolean {
  val goalBranch = request.goalContinuation?.goalBranch ?: return false
  val state = goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
    ?.takeIf { it.reviewCapReached || it.pausedForOperatorDecision }
    ?: return false
  val judgedDigest = state.reviewedDeltaDigest ?: return true
  val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
  val digests = listOfNotNull(state.remediationBaseSha, state.reviewBaseSha).distinct().mapNotNull { base ->
    phaseGates.gitOperations.buildGoalSubtaskReviewInput(
      request.repoRoot,
      reviewBaseline(request, resolved, state, base),
      goalBranch,
    ).input?.deltaDigest
  }
  return digests.isNotEmpty() && judgedDigest !in digests
}

internal fun FeatureTaskRuntimeRunner.reviewBaseline(
  request: FeatureTaskRuntimeRunRequest,
  resolved: FeatureTaskRuntimeResolvedBranch?,
  state: GoalSubtaskReviewState,
  reviewBaseSha: String,
): GoalSubtaskReviewBaseline = resolved
  ?.let { FeatureTaskRuntimeScopedReviewBaseline.of(phaseGates.gitOperations, request.repoRoot, it, reviewBaseSha) }
  ?: GoalSubtaskReviewBaseline(reviewBaseSha, state.baselineUntrackedPaths)

internal fun FeatureTaskRuntimeRunner.loadReviewFixIterationCount(request: FeatureTaskRuntimeRunRequest): Int =
  recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride)
    .orEmpty()
    .filter {
      it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
        it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }
    .mapNotNull { it.edgeIteration }
    .maxOrNull()
    ?: 0

internal fun FeatureTaskRuntimeRunner.loadAuditRepairProgress(
  request: FeatureTaskRuntimeRunRequest,
): FeatureTaskRuntimeAuditProgress = FeatureTaskRuntimeAuditConvergence.progressFrom(
  auditRecord = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
    ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
  auditGapIterationCount = loadAuditGapIterationCount(request),
)

internal fun FeatureTaskRuntimeRunner.loadFindingVerificationTelemetry(
  request: FeatureTaskRuntimeRunRequest,
): FeatureTaskRuntimeFindingVerificationTelemetry {
  val verifyRecord = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
    ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS)
    ?: return FeatureTaskRuntimeFindingVerificationTelemetry(
      reviewFixCapExhausted = loadReviewFixIterationCount(request) >= 1,
    )
  val outputMap = verifyRecord.outputArtifact
    ?.let(JsonSupport::parseObjectOrNull)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: return FeatureTaskRuntimeFindingVerificationTelemetry(
      reviewFixCapExhausted = loadReviewFixIterationCount(request) >= 1,
    )
  return FeatureTaskRuntimeFindingVerificationTelemetry(
    verifiedCount = FeatureTaskRuntimeOutputVerification.verifiedFindingDispositions(outputMap).size,
    rejectedCount = FeatureTaskRuntimeOutputVerification.rejectedFindingDispositions(outputMap).size,
    reviewFixCapExhausted = loadReviewFixIterationCount(request) >= 1,
  )
}

internal fun FeatureTaskRuntimeRunner.loadAuditGapIterationCount(request: FeatureTaskRuntimeRunRequest): Int =
  FeatureTaskRuntimeAuditConvergence.auditGapIterationCount(
    recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride).orEmpty(),
  )

internal fun FeatureTaskRuntimeRunner.loadRegenerationTelemetry(
  request: FeatureTaskRuntimeRunRequest,
): FeatureTaskRuntimeRegenerationTelemetry {
  val ledger = recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride).orEmpty()
  val regenFires = ledger.filter {
    it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
      FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(it.loopId.orEmpty())
  }
  val firedLoops = regenFires.mapNotNull { it.loopId }.toSet()
  val blocked = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
    .orEmpty()
    .values
    .filter { it.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED }
  val capExhaustedLoops = blocked
    .mapNotNull { it.loopId }
    .filter(FeatureTaskRuntimePhaseWorkflowDefinition::isRegenerationLoopId)
    .toSet()
  val unattributable = blocked.count {
    (it.blockedReason ?: "").contains("cannot attribute to a producing phase")
  }
  val producerNotInPipeline = blocked.count {
    (it.blockedReason ?: "").contains("absent from this run's resolved pipeline")
  }
  val regenerated = (firedLoops - capExhaustedLoops).size
  val outcomeCounts = buildMap {
    if (regenerated > 0) put("regenerated", regenerated)
    if (capExhaustedLoops.isNotEmpty()) put("cap_exhausted", capExhaustedLoops.size)
    if (unattributable > 0) put("unattributable", unattributable)
    if (producerNotInPipeline > 0) put("producer_not_in_pipeline", producerNotInPipeline)
  }
  return FeatureTaskRuntimeRegenerationTelemetry(
    activationCount = firedLoops.size,
    attemptCount = regenFires.size,
    outcomeCounts = outcomeCounts,
  )
}

internal fun FeatureTaskRuntimeRunner.finalizingAgentId(request: FeatureTaskRuntimeRunRequest): String? =
  agentAttributionFromPhaseState(recorder, request.workflowId, request.dbPathOverride).finalizingAgentId

internal val FeatureTaskRuntimeRunner.recorder get() = dependencies.recorder
internal val FeatureTaskRuntimeRunner.goalContinuationRecorder get() = dependencies.goalContinuationRecorder
internal val FeatureTaskRuntimeRunner.runInvariantsStore get() = dependencies.runInvariantsStore
internal val FeatureTaskRuntimeRunner.outputValidator get() = dependencies.outputValidator
internal val FeatureTaskRuntimeRunner.phaseGates get() = dependencies.phaseGates
internal val FeatureTaskRuntimeRunner.subtaskLauncher get() = dependencies.subtaskLauncher
internal val FeatureTaskRuntimeRunner.phaseSettlementService get() = dependencies.phaseSettlementService
internal val FeatureTaskRuntimeRunner.runnerDiagnostics get() = dependencies.diagnostics
internal val FeatureTaskRuntimeRunner.lifecycleTelemetry get() = phaseGates.lifecycleTelemetry
internal val FeatureTaskRuntimeRunner.specSourceResolver get() = phaseGates.specGate.specSourceResolver
