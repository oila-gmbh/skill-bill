package skillbill.application.featuretask

import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffInvalid
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffValid
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalised
import skillbill.contracts.JsonCodec
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

object FeatureTaskRuntimeRunLoopAttemptSettlement {
  internal fun rejectedOutputTargeting(args: RejectedOutputTargetingArgs): RejectedOutputTargeting =
    RejectedOutputTargeting(
      phaseId = args.phaseId,
      agentId = args.agentId,
      model = args.model,
      path = args.path,
      repairTurn = args.repairTurn,
    )

  internal fun gateOutput(runLoop: FeatureTaskRuntimeRunLoop, args: GateOutputArgs): AttemptResult {
    FeatureTaskRuntimeRunLoopAttemptSettlement.gateOutputEarlyExit(args)?.let { return it }
    FeatureTaskRuntimeRunLoopAttemptSettlement.settleFromPersistedEnvelope(runLoop, args)?.let { return it }
    return try {
      val run = args.run
      val acceptedOutput = runLoop.outputValidator
        .validatePhaseOutput(args.captured.text, sourceLabel = run.phaseId)
        .requireAcceptedOutput(run.phaseId)
      settleValidatedOutput(
        runLoop,
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
      FeatureTaskRuntimeRunLoopAttemptSettlement.gateOutputSchemaInvalid(runLoop, args, error)
    }
  }

  internal fun correctiveRepairContextForRejection(
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

  internal fun recordRejectedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: RecordRejectedOutputArgs,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val run = args.run
    val captured = args.captured
    val targeting = args.targeting
    return runLoop.recorder.recordRejectedOutput(
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
      runLoop.state.evidenceGeneration(targeting.phaseId),
    )
  }

  internal fun persistChildProcessFailureOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    reason: String,
    childOutput: FeatureTaskRuntimeChildOutput?,
  ) {
    val output = childOutput ?: return
    runCatching {
      recordRejectedOutput(
        runLoop,
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
      runLoop.diagnostics.warning(
        "Feature-task-runtime could not persist the child process-failure diagnostic for issue " +
          "${runLoop.request.issueKey}, workflow ${runLoop.request.workflowId}, phase '${run.phaseId}'. The block " +
          "reason keeps its bounded excerpt; the full child output is lost.",
        error,
      )
    }
  }

  internal fun settleValidatedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleValidatedOutputArgs,
  ): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val normalizedOutput = args.output.normalizedOutput
    val repairEvidence = args.output.repairEvidence
    val observability = args.output.observability
    val fileManifest = args.output.fileManifest
    val captured = args.output.captured
    val attested = FeatureTaskRuntimeRunLoopOutputVerification.attestAbsentGateValidationReceipt(
      runLoop,
      run,
      normalizedOutput,
    )
    val outputMap = attested.envelope
    val capture = ValidatedOutputCapture(
      run = run,
      iteration = iteration,
      captured = captured,
      repairEvidence = repairEvidence,
      fileManifest = fileManifest,
    )
    fun reject(rule: String, detail: String): AttemptResult =
      FeatureTaskRuntimeRunLoopAttemptSettlement.rejectValidatedOutput(
        runLoop,
        capture,
        outputMap,
        rule,
        detail,
      )
    FeatureTaskRuntimeRunLoopAttemptSettlement.settleValidatedOutputBoundary(
      runLoop,
      capture,
      outputMap,
      ::reject,
    )?.let { return it }
    FeatureTaskRuntimeRunLoopOutputVerification.firstValidatedOutputRejection(run.phaseId, outputMap)?.let { (
      rule,
      reason,
    ),
      ->
      return reject(rule, reason)
    }
    val fingerprintResolution = FeatureTaskRuntimeRunLoopAttemptSettlement.resolveRepositoryFingerprint(
      runLoop,
      run,
      iteration,
      runLoop.observability,
      fileManifest,
    )
    fingerprintResolution.blocked?.let { return it }
    return FeatureTaskRuntimeRunLoopAttemptSettlement.settleValidatedOutputAfterFingerprint(
      runLoop,
      SettleValidatedOutputAfterFingerprintArgs(
        capture = capture,
        outputMap = outputMap,
        attested = attested,
        repairEvidence = repairEvidence,
        observability = runLoop.observability,
        repositoryFingerprint = fingerprintResolution.fingerprint,
        reject = ::reject,
      ),
    )
  }

  internal fun settleFromPersistedEnvelope(runLoop: FeatureTaskRuntimeRunLoop, args: GateOutputArgs): AttemptResult? {
    val run = args.run
    val settlementEnvelope = runLoop.phaseSettlementService.findEnvelope(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      attempt = args.iteration,
      dbPathOverride = run.request.dbPathOverride,
    ) ?: return null
    return try {
      val acceptedOutput = runLoop.outputValidator
        .validatePhaseOutput(
          JsonCodec.mapToJsonString(settlementEnvelope),
          sourceLabel = run.phaseId,
        )
        .requireAcceptedOutput(run.phaseId)
      FeatureTaskRuntimeRunLoopAttemptSettlement.settleValidatedOutput(
        runLoop,
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
      rejectPersistedEnvelopeSchema(runLoop, args, run, error)
      null
    }
  }

  private fun rejectPersistedEnvelopeSchema(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: GateOutputArgs,
    run: PhaseRun,
    error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  ) {
    runLoop.phaseSettlementService.clear(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      attempt = args.iteration,
      dbPathOverride = run.request.dbPathOverride,
    )
    FeatureTaskRuntimeRunLoopOutputVerification.persistVerifyFindingsCheckpointIfPresent(
      runLoop,
      run,
      args.captured.text,
    )
    FeatureTaskRuntimeRunLoopAttemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = run,
        iteration = args.iteration,
        rule = "phase-settlement-schema",
        reason = error.reason,
        captured = args.captured,
        targeting = FeatureTaskRuntimeRunLoopAttemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(
            run,
            RejectedOutputTargetingOverrides(
              path = FeatureTaskRuntimeRunLoopRecordRejection.rejectionPath(error.reason),
            ),
          ),
        ),
      ),
    )
  }
  internal fun gateOutputEarlyExit(args: GateOutputArgs): AttemptResult? {
    val run = args.run
    if (run.validationGateRepairTurn > 0) {
      val outputMap = FeatureTaskRuntimeRunLoopValidationGate.looseOutputEnvelope(args.captured.text)
      val operatorTerminalQualityGate = outputMap?.let { envelope ->
        val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, envelope)
        !disposition.retryOnResume &&
          (
            run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
              run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
            )
      } == true
      if (!operatorTerminalQualityGate) {
        val gateRepairOutput = FeatureTaskRuntimeRunLoopValidationGate.gateRepairSegmentOutput(
          run,
          args.iteration,
        )
        return AttemptResult.settled(PhaseOutcome.completed(gateRepairOutput))
      }
    }
    if (run.validationGateTriage) {
      return AttemptResult.settled(
        PhaseOutcome.completed(
          FeatureTaskRuntimeRunLoopValidationGate.gateTriageSegmentOutput(
            run,
            args.iteration,
            args.captured.text,
          ),
        ),
      )
    }
    return null
  }
  internal fun gateOutputSchemaInvalid(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: GateOutputArgs,
    error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  ): AttemptResult {
    val run = args.run
    FeatureTaskRuntimeRunLoopOutputVerification.persistVerifyFindingsCheckpointIfPresent(
      runLoop,
      run,
      args.captured.text,
    )
    val path = FeatureTaskRuntimeRunLoopRecordRejection.rejectionPath(error.reason)
    val reason = FeatureTaskRuntimeRunLoopRecordRejection.payloadFreeRejectionReason("phase-output-schema", path)
    val diagnosticWrite = FeatureTaskRuntimeRunLoopAttemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = run,
        iteration = args.iteration,
        rule = "phase-output-schema",
        reason = error.reason,
        captured = args.captured,
        targeting = FeatureTaskRuntimeRunLoopAttemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(run, RejectedOutputTargetingOverrides(path = path)),
        ),
      ),
    )
    val repairEvidence = FeatureTaskRuntimeRunLoopOutputVerification
      .structuralRepairEvidenceFromSchemaError(error)
    return FeatureTaskRuntimeRunLoopOutputPersistence.schemaInvalidAttempt(
      reason,
      args.fileManifest,
      malformedOutput = error.failureKind == FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED,
      retryReason = FeatureTaskRuntimeRunLoopRecordRejection.retryRejectionReason(reason, error.payloadFreeReason),
      correctiveRepairContext = FeatureTaskRuntimeRunLoopAttemptSettlement.correctiveRepairContextForRejection(
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

  internal fun resolveRepositoryFingerprint(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): RepositoryFingerprintResolution {
    val result = FeatureTaskRuntimeRunLoopOutputVerification.completedPhaseRepositoryFingerprint(
      runLoop,
      run,
    ) ?: return RepositoryFingerprintResolution(null, null)
    if (!result.ok) {
      val blocked = AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
          runLoop,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = "Completed-phase repository fingerprinting failed for '${run.phaseId}': ${result.error}",
            observability = observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          ),
        ),
      )
      return RepositoryFingerprintResolution(fingerprint = null, blocked = blocked)
    }
    return RepositoryFingerprintResolution(result.value, null)
  }

  internal fun settleValidatedOutputBoundary(
    runLoop: FeatureTaskRuntimeRunLoop,
    capture: ValidatedOutputCapture,
    outputMap: Map<String, Any?>,
    reject: (String, String) -> AttemptResult,
  ): AttemptResult? {
    val bodyDelivery = FeatureTaskRuntimeRunLoopOutputVerification
      .findingVerificationBoundaryBodyDeliveryDecision(runLoop, capture.run, outputMap)
    return when (bodyDelivery) {
      is BoundaryBodyDeliveryDecision.RejectDecision -> reject("output-verification", bodyDelivery.reason)
      is BoundaryBodyDeliveryDecision.ContinueDecision ->
        AttemptResult.boundaryBodyDelivery(bodyDelivery.reason, capture.fileManifest)
      BoundaryBodyDeliveryDecision.NotApplicable -> null
    }
  }

  internal fun settleValidatedOutputPauseOrTerminal(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleValidatedOutputPauseArgs,
  ): AttemptResult? {
    val capture = args.capture
    val outputMap = args.outputMap
    val attested = args.attested
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val repositoryFingerprint = args.repositoryFingerprint
    val run = capture.run
    FeatureTaskRuntimeRunLoopOutputVerification.auditGapProgressPause(
      runLoop,
      run,
      outputMap,
      repositoryFingerprint,
      attested.canonicalJson,
    )?.let { pause ->
      FeatureTaskRuntimeRunLoopPlanningBranch.mintAuditGapPause(runLoop, pause, run.phaseId, attested.canonicalJson)
      return AttemptResult.settled(PhaseOutcome.paused(pause.reason))
    }
    terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
      return FeatureTaskRuntimeRunLoopOutputVerification.terminalOutputAttempt(
        runLoop,
        TerminalOutputAttemptArgs(
          run = run,
          iteration = capture.iteration,
          reason = reason,
          outputMap = outputMap,
          normalizedOutput = attested,
          repairEvidence = repairEvidence,
          observability = runLoop.observability,
          fileManifest = capture.fileManifest,
        ),
      )
    }
    return null
  }

  internal fun settleValidatedOutputCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
  ): Pair<NormalizedFeatureTaskRuntimePhaseOutput, AttemptResult?> = when (
    val finalisation = FeatureTaskRuntimeRunLoopAttemptSettlement.finaliseSubtaskCommit(
      runLoop,
      run,
      attested,
    )
  ) {
    is CommitPushNotApplicable -> attested to null
    is CommitPushSettled -> finalisation.output to null
    is CommitPushBlocked -> attested to AttemptResult.settled(
      FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersistInPhase(
        runLoop,
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

  internal fun settleValidatedOutputAfterFingerprint(
    runLoop: FeatureTaskRuntimeRunLoop,
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
      runLoop,
      SettleValidatedOutputPauseArgs(
        capture = capture,
        outputMap = outputMap,
        attested = attested,
        repairEvidence = repairEvidence,
        observability = runLoop.observability,
        repositoryFingerprint = repositoryFingerprint,
      ),
    )?.let { return it }
    val run = capture.run
    FeatureTaskRuntimeRunLoopOutputVerification.completionProjectionRejection(
      runLoop,
      CompletionProjectionRejectionArgs(
        run = run,
        iteration = capture.iteration,
        outputMap = outputMap,
        normalizedOutput = attested,
        repairEvidence = repairEvidence,
        repositoryFingerprint = repositoryFingerprint,
      ),
    )?.let { (rule, reason) -> return reject(rule, reason) }
    FeatureTaskRuntimeRunLoopRepairReceipt.settleCompletedImplementationOutput(
      runLoop,
      CompletedImplementationOutputArgs(
        run = run,
        outputMap = outputMap,
        reject = reject,
        iteration = capture.iteration,
        observability = runLoop.observability,
        fileManifest = capture.fileManifest,
      ),
    )?.let { return it }
    return finalizeValidatedOutputAcceptance(
      runLoop,
      FinalizeValidatedOutputAcceptanceArgs(
        capture = capture,
        attested = attested,
        repairEvidence = repairEvidence,
        observability = runLoop.observability,
        repositoryFingerprint = repositoryFingerprint,
      ),
    )
  }

  internal fun finalizeValidatedOutputAcceptance(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: FinalizeValidatedOutputAcceptanceArgs,
  ): AttemptResult {
    val capture = args.capture
    val attested = args.attested
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val repositoryFingerprint = args.repositoryFingerprint
    val run = capture.run
    val (finalised, commitBlocked) = settleValidatedOutputCommit(runLoop, run, capture, attested, observability)
    commitBlocked?.let { return it }
    FeatureTaskRuntimeRunLoopAttemptSettlement.retainSettledProducerOutput(runLoop, capture)
    return FeatureTaskRuntimeRunLoopOutputVerification.persistAcceptedOutput(
      runLoop,
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

  internal fun rejectValidatedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    capture: ValidatedOutputCapture,
    outputMap: Map<String, Any?>,
    rule: String,
    detail: String,
  ): AttemptResult {
    val diagnosticRule = rule
    val path = FeatureTaskRuntimeRunLoopRecordRejection.rejectionPath(detail)
    val reason = FeatureTaskRuntimeRunLoopRecordRejection.payloadFreeRejectionReason(rule, path)
    val retryFacingConstraint = FeatureTaskRuntimeRunLoopRecordRejection.payloadFreeSemanticGateConstraint(
      runLoop,
      rule,
      detail,
      outputMap,
    )
    val retryReason = FeatureTaskRuntimeRunLoopRecordRejection.retryRejectionReason(reason, retryFacingConstraint)
    val diagnosticWrite = FeatureTaskRuntimeRunLoopAttemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = capture.run,
        iteration = capture.iteration,
        rule = diagnosticRule,
        reason = detail,
        captured = capture.captured,
        targeting = FeatureTaskRuntimeRunLoopAttemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(capture.run, RejectedOutputTargetingOverrides(path = path)),
        ),
      ),
    )
    return FeatureTaskRuntimeRunLoopOutputPersistence.schemaInvalidAttempt(
      reason,
      capture.fileManifest,
      retryReason = retryReason,
      correctiveRepairContext = FeatureTaskRuntimeRunLoopAttemptSettlement.correctiveRepairContextForRejection(
        CorrectiveRepairRejectionArgs(
          run = capture.run,
          iteration = capture.iteration,
          captured = capture.captured,
          diagnosticWrite = diagnosticWrite,
          rejection = CorrectiveRepairRejectionDetail(
            rule = diagnosticRule,
            path = path,
            payloadFreeConstraint = retryFacingConstraint ?: reason,
            acceptedAfterStructuralRepair = capture.repairEvidence != null,
            structuralRepairEvidence = capture.repairEvidence,
          ),
        ),
      ),
    )
  }

  fun retainSettledProducerOutput(runLoop: FeatureTaskRuntimeRunLoop, capture: ValidatedOutputCapture) {
    val run = capture.run
    runLoop.recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = runLoop.request.workflowId,
        phaseId = run.phaseId,
        attempt = capture.iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = runLoop.clock.instant(),
        byteSize = capture.outputByteSize,
        sha256 = capture.outputSha256,
        payload = capture.outputBytes.takeUnless { capture.outputTruncated },
        generation = runLoop.state.evidenceGeneration(run.phaseId),
        repairTurn = run.validationGateRepairTurn,
      ),
      run.request.dbPathOverride,
    )
  }

  internal fun finaliseSubtaskCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): CommitPushFinalisation {
    if (
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ||
      normalizedOutput.envelope["status"] != STATUS_COMPLETED
    ) {
      return CommitPushNotApplicable
    }
    val subtaskCommit = FeatureTaskRuntimeRunLoopSubtaskCommit
    val branch = subtaskCommit.finalisationBranch(runLoop)
      ?: return subtaskCommit.unownedWorktreeCommitSha(runLoop, run, normalizedOutput)
    val handoff = when (val read = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(normalizedOutput.envelope)) {
      is FeatureTaskRuntimeCommitPushHandoffInvalid -> return CommitPushBlocked(read.reason)
      is FeatureTaskRuntimeCommitPushHandoffValid -> read.handoff
    }
    val identity = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitIdentity(runLoop)
    val ledger = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitLedgerState(runLoop, identity)
    val outcome = FeatureTaskRuntimeSubtaskFinalisation(
      gitOperations = runLoop.phaseGates.gitOperations,
      repoRoot = runLoop.request.repoRoot,
      record = { record -> runCatching { runLoop.diagnostics.warning(record) } },
      recordCommit = { commitSha, stagedPaths ->
        FeatureTaskRuntimeRunLoopSubtaskCommit.recordFinalisedCheckpointIdentity(
          runLoop,
          RecordFinalisedCheckpointIdentityArgs(run.phaseId, branch, ledger, commitSha, stagedPaths),
        )
      },
    ).finalise(
      FeatureTaskRuntimeSubtaskFinaliseRequest(
        identity = identity,
        durableCommitSha = ledger.commitSha,
        sequenceNumber = ledger.nextSequenceNumber,
        handoff = handoff,
        metadata = FeatureTaskRuntimeCheckpointMetadata(
          phaseId = run.phaseId,
          loopId = null,
          generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(runLoop, null),
          branch = branch,
          intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
        ),
        manifestCommitSha = runLoop.goalContinuationManifestCommitSha,
      ),
    )
    return when (outcome) {
      is FeatureTaskRuntimeSubtaskFinalisationBlocked -> CommitPushBlocked(outcome.reason)
      is FeatureTaskRuntimeSubtaskFinalised -> CommitPushSettled(
        FeatureTaskRuntimeRunLoopSubtaskCommit.revalidated(
          runLoop,
          run.phaseId,
          FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(normalizedOutput.envelope, outcome.commitSha),
        ),
      )
    }
  }
}

val FeatureTaskRuntimeRunLoop.goalContinuationManifestCommitSha: String?
  get() = null
