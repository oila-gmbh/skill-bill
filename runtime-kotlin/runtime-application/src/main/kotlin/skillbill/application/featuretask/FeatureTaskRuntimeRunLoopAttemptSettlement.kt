package skillbill.application.featuretask

import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun rejectedOutputTargeting(args: RejectedOutputTargetingArgs): RejectedOutputTargeting =
  RejectedOutputTargeting(
    phaseId = args.phaseId,
    agentId = args.agentId,
    model = args.model,
    path = args.path,
    repairTurn = args.repairTurn,
  )

internal fun FeatureTaskRuntimeRunLoop.gateOutput(args: GateOutputArgs): AttemptResult {
  gateOutputEarlyExit(args)?.let { return it }
  settleFromPersistedEnvelope(args)?.let { return it }
  return try {
    val run = args.run
    val acceptedOutput = outputValidator
      .validatePhaseOutput(args.captured.text, sourceLabel = run.phaseId)
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
    gateOutputSchemaInvalid(args, error)
  }
}

internal fun FeatureTaskRuntimeRunLoop.correctiveRepairContextForRejection(
  args: CorrectiveRepairRejectionArgs,
): FeatureTaskRuntimeCorrectiveRepairContext {
  val run = args.run
  val iteration = args.iteration
  val captured = args.captured
  val diagnosticWrite = args.diagnosticWrite
  val rejection = args.rejection
  val utf8ByteCount = captured.byteSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
  val capturedResponse = if (captured.truncated) {
    CorrectiveRepairCapturedResponse.AlreadyTruncated(
      utf8ByteCount = utf8ByteCount,
      digestSha256 = captured.sha256,
    )
  } else {
    CorrectiveRepairCapturedResponse.classify(
      body = captured.text,
      alreadyTruncated = false,
      knownUtf8ByteCount = utf8ByteCount,
      knownDigestSha256 = captured.sha256,
    )
  }
  val repairEvidence = rejection.structuralRepairEvidence
  val locator = (diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.let {
    CorrectiveRepairDiagnosticLocator(it.identity)
  }
  val degradationClass = (diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Degraded)?.failureClass
  return FeatureTaskRuntimeCorrectiveRepairContext(
    phaseId = run.phaseId,
    attempt = iteration.coerceAtLeast(1),
    repairTurn = run.validationGateRepairTurn.takeIf { it > 0 },
    rejectionRule = rejection.rule,
    rejectionPath = rejection.path,
    payloadFreeConstraint = rejection.payloadFreeConstraint,
    diagnosticLocator = locator,
    captured = capturedResponse,
    acceptedAfterStructuralRepair = rejection.acceptedAfterStructuralRepair || repairEvidence != null,
    structuralRepairEvidence = repairEvidence,
    diagnosticDegradationClass = degradationClass,
  )
}

internal fun FeatureTaskRuntimeRunLoop.recordRejectedOutput(
  args: RecordRejectedOutputArgs,
): FeatureTaskRuntimeRejectedOutputWrite {
  val run = args.run
  val captured = args.captured
  val targeting = args.targeting
  return recorder.recordRejectedOutput(
    RejectedOutputDiagnosticRequest(
      workflowId = run.request.workflowId,
      phaseId = targeting.phaseId,
      attempt = args.iteration.coerceAtLeast(1),
      rule = args.rule,
      path = targeting.path,
      reason = args.reason,
      agentId = targeting.agentId,
      model = targeting.model,
      rawResponse = captured.bytes,
      observedByteSize = captured.byteSize,
      observedSha256 = captured.sha256,
      truncated = captured.truncated,
      repairTurn = targeting.repairTurn,
    ),
    run.request.dbPathOverride,
    state.evidenceGeneration(targeting.phaseId),
  )
}

internal fun FeatureTaskRuntimeRunLoop.persistChildProcessFailureOutput(
  run: PhaseRun,
  iteration: Int,
  reason: String,
  childOutput: FeatureTaskRuntimeChildOutput?,
) {
  val output = childOutput ?: return
  runCatching {
    recordRejectedOutput(
      RecordRejectedOutputArgs(
        run = run,
        iteration = iteration,
        rule = FEATURE_TASK_RUNTIME_PROCESS_FAILURE_RULE,
        reason = boundedSchemaGateDetail(reason),
        captured = CapturedPhaseOutput.fromBytes(output.storedBody().encodeToByteArray()),
        targeting = rejectedOutputTargeting(defaultRejectedOutputTargetingArgs(run)),
      ),
    )
  }.onFailure { error ->
    diagnostics.warning(
      "Feature-task-runtime could not persist the child process-failure diagnostic for issue " +
        "${request.issueKey}, workflow ${request.workflowId}, phase '${run.phaseId}'. The block " +
        "reason keeps its bounded excerpt; the full child output is lost.",
      error,
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.settleValidatedOutput(args: SettleValidatedOutputArgs): AttemptResult {
  val run = args.run
  val iteration = args.iteration
  val normalizedOutput = args.output.normalizedOutput
  val repairEvidence = args.output.repairEvidence
  val observability = args.output.observability
  val fileManifest = args.output.fileManifest
  val captured = args.output.captured
  val attested = attestAbsentGateValidationReceipt(run, normalizedOutput)
  val outputMap = attested.envelope
  val capture = ValidatedOutputCapture(
    run = run,
    iteration = iteration,
    captured = captured,
    repairEvidence = repairEvidence,
    fileManifest = fileManifest,
  )
  fun reject(rule: String, detail: String): AttemptResult = rejectValidatedOutput(capture, outputMap, rule, detail)
  settleValidatedOutputBoundary(capture, outputMap, ::reject)?.let { return it }
  firstValidatedOutputRejection(run.phaseId, outputMap)?.let { (rule, reason) -> return reject(rule, reason) }
  val fingerprintResolution = resolveRepositoryFingerprint(run, iteration, observability, fileManifest)
  fingerprintResolution.blocked?.let { return it }
  return settleValidatedOutputAfterFingerprint(
    SettleValidatedOutputAfterFingerprintArgs(
      capture = capture,
      outputMap = outputMap,
      attested = attested,
      repairEvidence = repairEvidence,
      observability = observability,
      repositoryFingerprint = fingerprintResolution.fingerprint,
      reject = ::reject,
    ),
  )
}
