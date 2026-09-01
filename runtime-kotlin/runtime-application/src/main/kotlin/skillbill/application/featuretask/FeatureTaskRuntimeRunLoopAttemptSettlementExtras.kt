package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.settleFromPersistedEnvelope(args: GateOutputArgs): AttemptResult? {
  val run = args.run
  val settlementEnvelope = phaseSettlementService.findEnvelope(
    workflowId = run.request.workflowId,
    phaseId = run.phaseId,
    attempt = args.iteration,
    dbPathOverride = run.request.dbPathOverride,
  ) ?: return null
  return try {
    val acceptedOutput = outputValidator
      .validatePhaseOutput(
        JsonSupport.mapToJsonString(settlementEnvelope),
        sourceLabel = run.phaseId,
      )
      .requireAcceptedOutput(run.phaseId)
    settleValidatedOutput(
      SettleValidatedOutputArgs(
        run = run,
        iteration = args.iteration,
        output = SettledOutputContext(
          normalizedOutput = acceptedOutput.normalizedOutput,
          repairEvidence = acceptedOutput.repairEvidence,
          observability = args.observability,
          fileManifest = args.fileManifest,
          captured = args.captured,
        ),
      ),
    )
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
    phaseSettlementService.clear(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      attempt = args.iteration,
      dbPathOverride = run.request.dbPathOverride,
    )
    persistVerifyFindingsCheckpointIfPresent(run, args.captured.text)
    recordRejectedOutput(
      RecordRejectedOutputArgs(
        run = run,
        iteration = args.iteration,
        rule = "phase-settlement-schema",
        reason = error.reason,
        captured = args.captured,
        targeting = rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(
            run,
            RejectedOutputTargetingOverrides(path = rejectionPath(error.reason)),
          ),
        ),
      ),
    )
    null
  }
}

internal fun FeatureTaskRuntimeRunLoop.gateOutputEarlyExit(args: GateOutputArgs): AttemptResult? {
  val run = args.run
  if (run.validationGateRepairTurn > 0) {
    val outputMap = looseOutputEnvelope(args.captured.text)
    val operatorTerminalQualityGate = outputMap?.let { envelope ->
      val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, envelope)
      !disposition.retryOnResume &&
        (
          run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
            run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
          )
    } == true
    if (!operatorTerminalQualityGate) {
      return AttemptResult.settled(PhaseOutcome.completed(gateRepairSegmentOutput(run, args.iteration)))
    }
  }
  if (run.validationGateTriage) {
    return AttemptResult.settled(
      PhaseOutcome.completed(
        gateTriageSegmentOutput(
          run,
          args.iteration,
          args.captured.text,
        ),
      ),
    )
  }
  return null
}

internal fun FeatureTaskRuntimeRunLoop.gateOutputSchemaInvalid(
  args: GateOutputArgs,
  error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
): AttemptResult {
  val run = args.run
  persistVerifyFindingsCheckpointIfPresent(run, args.captured.text)
  val path = rejectionPath(error.reason)
  val reason = payloadFreeRejectionReason("phase-output-schema", path)
  val diagnosticWrite = recordRejectedOutput(
    RecordRejectedOutputArgs(
      run = run,
      iteration = args.iteration,
      rule = "phase-output-schema",
      reason = error.reason,
      captured = args.captured,
      targeting = rejectedOutputTargeting(
        defaultRejectedOutputTargetingArgs(run, RejectedOutputTargetingOverrides(path = path)),
      ),
    ),
  )
  val repairEvidence = structuralRepairEvidenceFromSchemaError(error)
  return schemaInvalidAttempt(
    reason,
    args.fileManifest,
    malformedOutput = error.failureKind == FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED,
    retryReason = retryRejectionReason(reason, error.payloadFreeReason),
    correctiveRepairContext = correctiveRepairContextForRejection(
      CorrectiveRepairRejectionArgs(
        run = run,
        iteration = args.iteration,
        captured = args.captured,
        diagnosticWrite = diagnosticWrite,
        rejection = CorrectiveRepairRejectionDetail(
          rule = "phase-output-schema",
          path = path,
          payloadFreeConstraint = error.payloadFreeReason.orEmpty(),
          acceptedAfterStructuralRepair = error.acceptedAfterStructuralRepair,
          structuralRepairEvidence = repairEvidence,
        ),
      ),
    ),
  )
}

internal data class RepositoryFingerprintResolution(
  val fingerprint: String?,
  val blocked: AttemptResult?,
)

internal fun FeatureTaskRuntimeRunLoop.resolveRepositoryFingerprint(
  run: PhaseRun,
  iteration: Int,
  observability: FeatureTaskRuntimeRunObservability,
  fileManifest: FeatureTaskRuntimePhaseFileManifest,
): RepositoryFingerprintResolution {
  val result = completedPhaseRepositoryFingerprint(run) ?: return RepositoryFingerprintResolution(null, null)
  if (!result.ok) {
    return RepositoryFingerprintResolution(
      fingerprint = null,
      blocked = AttemptResult.settled(
        blockInPhase(
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = "Completed-phase repository fingerprinting failed for '${run.phaseId}': ${result.error}",
            observability = observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          ),
        ),
      ),
    )
  }
  return RepositoryFingerprintResolution(result.value, null)
}

internal fun FeatureTaskRuntimeRunLoop.settleValidatedOutputBoundary(
  capture: ValidatedOutputCapture,
  outputMap: Map<String, Any?>,
  reject: (String, String) -> AttemptResult,
): AttemptResult? = when (val bodyDelivery = findingVerificationBoundaryBodyDeliveryDecision(capture.run, outputMap)) {
  is BoundaryBodyDeliveryDecision.RejectDecision -> reject("output-verification", bodyDelivery.reason)
  is BoundaryBodyDeliveryDecision.ContinueDecision ->
    AttemptResult.boundaryBodyDelivery(bodyDelivery.reason, capture.fileManifest)
  BoundaryBodyDeliveryDecision.NotApplicable -> null
}

internal fun FeatureTaskRuntimeRunLoop.settleValidatedOutputPauseOrTerminal(
  args: SettleValidatedOutputPauseArgs,
): AttemptResult? {
  val capture = args.capture
  val outputMap = args.outputMap
  val attested = args.attested
  val repairEvidence = args.repairEvidence
  val observability = args.observability
  val repositoryFingerprint = args.repositoryFingerprint
  val run = capture.run
  auditGapProgressPause(run, outputMap, repositoryFingerprint, attested.canonicalJson)?.let { pause ->
    mintAuditGapPause(pause, run.phaseId, attested.canonicalJson)
    return AttemptResult.settled(PhaseOutcome.paused(pause.reason))
  }
  terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
    return terminalOutputAttempt(
      TerminalOutputAttemptArgs(
        run = run,
        iteration = capture.iteration,
        reason = reason,
        outputMap = outputMap,
        normalizedOutput = attested,
        repairEvidence = repairEvidence,
        observability = observability,
        fileManifest = capture.fileManifest,
      ),
    )
  }
  return null
}

internal fun FeatureTaskRuntimeRunLoop.settleValidatedOutputCommit(
  run: PhaseRun,
  capture: ValidatedOutputCapture,
  attested: NormalizedFeatureTaskRuntimePhaseOutput,
  observability: FeatureTaskRuntimeRunObservability,
): Pair<NormalizedFeatureTaskRuntimePhaseOutput, AttemptResult?> = when (
  val finalisation = finaliseSubtaskCommit(
    run,
    attested,
  )
) {
  is CommitPushNotApplicable -> attested to null
  is CommitPushSettled -> finalisation.output to null
  is CommitPushBlocked -> attested to AttemptResult.settled(
    blockAndPersistInPhase(
      phaseBlockArgs(
        run,
        capture.iteration,
        finalisation.reason,
        observability,
        payload = BlockAndPersistPayload(fileManifest = capture.fileManifest),
      ).withDisposition(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION),
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.settleValidatedOutputAfterFingerprint(
  args: SettleValidatedOutputAfterFingerprintArgs,
): AttemptResult {
  val capture = args.capture
  val outputMap = args.outputMap
  val attested = args.attested
  val repairEvidence = args.repairEvidence
  val observability = args.observability
  val repositoryFingerprint = args.repositoryFingerprint
  val reject = args.reject
  settleValidatedOutputPauseOrTerminal(
    SettleValidatedOutputPauseArgs(
      capture = capture,
      outputMap = outputMap,
      attested = attested,
      repairEvidence = repairEvidence,
      observability = observability,
      repositoryFingerprint = repositoryFingerprint,
    ),
  )?.let { return it }
  val run = capture.run
  completionProjectionRejection(
    CompletionProjectionRejectionArgs(
      run = run,
      iteration = capture.iteration,
      outputMap = outputMap,
      normalizedOutput = attested,
      repairEvidence = repairEvidence,
      repositoryFingerprint = repositoryFingerprint,
    ),
  )?.let { (rule, reason) -> return reject(rule, reason) }
  settleCompletedImplementationOutput(
    CompletedImplementationOutputArgs(
      run = run,
      outputMap = outputMap,
      reject = reject,
      iteration = capture.iteration,
      observability = observability,
      fileManifest = capture.fileManifest,
    ),
  )?.let { return it }
  return finalizeValidatedOutputAcceptance(
    capture,
    attested,
    repairEvidence,
    observability,
    repositoryFingerprint,
  )
}

internal fun FeatureTaskRuntimeRunLoop.finalizeValidatedOutputAcceptance(
  capture: ValidatedOutputCapture,
  attested: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  observability: FeatureTaskRuntimeRunObservability,
  repositoryFingerprint: String?,
): AttemptResult {
  val run = capture.run
  val (finalised, commitBlocked) = settleValidatedOutputCommit(run, capture, attested, observability)
  commitBlocked?.let { return it }
  retainSettledProducerOutput(capture)
  return persistAcceptedOutput(
    PersistAcceptedOutputArgs(
      run = run,
      iteration = capture.iteration,
      normalizedOutput = finalised,
      repairEvidence = repairEvidence,
      observability = observability,
      fileManifest = capture.fileManifest,
      repositoryFingerprint = repositoryFingerprint,
    ),
  )
}
