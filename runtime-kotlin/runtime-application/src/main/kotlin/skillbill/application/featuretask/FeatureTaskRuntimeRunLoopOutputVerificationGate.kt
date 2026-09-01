package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.structuredFindings
import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress

internal fun FeatureTaskRuntimeRunLoop.completedPhaseRepositoryFingerprint(run: PhaseRun) = if (
  run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ||
  run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
) {
  gitOperations.repositoryFingerprint(run.request.repoRoot)
} else {
  null
}

internal fun FeatureTaskRuntimeRunLoop.auditGapProgressPause(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
  repositoryFingerprint: String?,
  auditOutputArtifact: String,
): FeatureTaskRuntimeAuditGapPause? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
  if (auditOutputArtifact.isBlank()) return null
  val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(run.phaseId, outputMap)
  val currentHasGaps = verdict == FeatureTaskRuntimeVerdict.GAPS_FOUND
  val previous = recorder.loadAuditGapProgress(request.workflowId, request.dbPathOverride)
  val decision = if (previous == null || !currentHasGaps) {
    FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  } else {
    detectAuditRepairNonProgress(
      previousHadGaps = previous.criterionRefs.isNotEmpty(),
      currentHasGaps = true,
      previousRepositoryFingerprint = previous.repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
      currentRepositoryFingerprint = repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
    )
  }
  if (currentHasGaps) {
    recorder.persistAuditGapProgress(
      request.workflowId,
      FeatureTaskRuntimeAuditGapProgress(
        criterionRefs = setOf(FeatureTaskRuntimeAuditGapProgress.HAD_GAPS_MARKER),
        repositoryFingerprint = repositoryFingerprint,
      ),
      request.dbPathOverride,
    )
  } else {
    recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)?.let { pause ->
      if (!pause.grantConsumed || pause.operatorDecision != null) {
        consumeAuditGapRetryGrant(pause)
      }
    }
  }
  if (!decision.blocked) return null
  return FeatureTaskRuntimeAuditGapPause(
    pauseKind = AUDIT_GAP_PAUSE_KIND_NO_PROGRESS,
    reason = noProgressPauseReason(requireNotNull(decision.reason)),
    edgeIteration = state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) + 1,
  )
}

internal fun FeatureTaskRuntimeRunLoop.noProgressPauseReason(decisionReason: String): String =
  "$decisionReason The subtask is paused for an operator decision: choose retry_fix to allow one " +
    "further remediation attempt, or abandon_subtask to end the subtask."

internal fun FeatureTaskRuntimeRunLoop.terminalOutputAttempt(args: TerminalOutputAttemptArgs): AttemptResult {
  val run = args.run
  val iteration = args.iteration
  val reason = args.reason
  val outputMap = args.outputMap
  val normalizedOutput = args.normalizedOutput
  val repairEvidence = args.repairEvidence
  val observability = args.observability
  val fileManifest = args.fileManifest
  val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, outputMap)
  val operatorTerminalQualityGate =
    !disposition.retryOnResume &&
      (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD)
  if (operatorTerminalQualityGate) {
    return AttemptResult.settled(
      blockInPhase(
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = reason,
          observability = observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
          failureDisposition = disposition,
        ),
      ),
    )
  }
  return if (
    disposition.retryOnResume &&
    FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)
  ) {
    // A retryable blocked/failed envelope re-enters the semantic fix loop as itself. It is NOT
    // relabelled schema-invalid: it validated, and converting it would both misreport the run and
    // charge the structural-repair budget for a document with nothing structurally wrong.
    AttemptResult.retryableTerminal(reason, fileManifest, disposition)
  } else {
    AttemptResult.settled(
      blockInPhase(
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = reason,
          observability = observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
          failureDisposition = disposition,
        ),
      ),
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.outputVerificationGateReason(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): String? = findingVerificationBoundaryDispositionGate(run, outputMap)
  ?: FeatureTaskRuntimeVerificationGateReasons.reviewVerificationSignal(run.phaseId, outputMap)
  ?: FeatureTaskRuntimeVerificationGateReasons.findingVerificationDisposition(
    run.phaseId,
    outputMap,
    reviewFindingIdsForVerification(),
  )

internal fun FeatureTaskRuntimeRunLoop.findingVerificationBoundarySections(
  run: PhaseRun,
): List<FeatureTaskRuntimeFindingBoundaryMemorySection> {
  val reviewOutput = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    ?.normalizedOutput?.envelope
  val recordedVerdicts = reviewOutput?.let {
    recorder.recordedFindingVerdicts(
      it,
      run.request.dbPathOverride,
    )
  }.orEmpty()
  val findings = reviewOutput?.let {
    GoalSubtaskReviewSummaryReducer.structuredFindings(it, recordedVerdicts)
  }.orEmpty()
  return phaseGates.findingVerificationBoundaryMemory.sectionsForFindings(
    run.request.repoRoot,
    findings.mapNotNull { finding ->
      val findingId = finding.findingId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      FeatureTaskRuntimeFindingBoundaryMemoryRequest(
        findingId = findingId,
        findingPaths = findingPathsForBoundaryMemory(finding),
      )
    },
  )
}

// Every return is a distinct delivery decision — NotApplicable, Reject, or Continue — and the
// sibling disposition gate suppresses this rule for the same reason.
