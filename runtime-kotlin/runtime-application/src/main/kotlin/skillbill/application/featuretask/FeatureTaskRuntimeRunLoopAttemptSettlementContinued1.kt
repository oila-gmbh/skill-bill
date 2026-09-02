package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopAttemptSettlementContinued1 {
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
          JsonSupport.mapToJsonString(settlementEnvelope),
          sourceLabel = run.phaseId,
        )
        .requireAcceptedOutput(run.phaseId)
      runLoop.collaborators.attemptSettlement.settleValidatedOutput(
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
    runLoop.collaborators.outputVerificationContinued2.persistVerifyFindingsCheckpointIfPresent(
      runLoop,
      run,
      args.captured.text,
    )
    runLoop.collaborators.attemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = run,
        iteration = args.iteration,
        rule = "phase-settlement-schema",
        reason = error.reason,
        captured = args.captured,
        targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(
            run,
            RejectedOutputTargetingOverrides(
              path = runLoop.collaborators.recordRejection.rejectionPath(error.reason),
            ),
          ),
        ),
      ),
    )
  }
  internal fun gateOutputEarlyExit(runLoop: FeatureTaskRuntimeRunLoop, args: GateOutputArgs): AttemptResult? {
    val run = args.run
    if (run.validationGateRepairTurn > 0) {
      val outputMap = runLoop.collaborators.validationGateContinued1.looseOutputEnvelope(args.captured.text)
      val operatorTerminalQualityGate = outputMap?.let { envelope ->
        val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, envelope)
        !disposition.retryOnResume &&
          (
            run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
              run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
            )
      } == true
      if (!operatorTerminalQualityGate) {
        val gateRepairOutput = runLoop.collaborators.validationGateContinued1.gateRepairSegmentOutput(
          run,
          args.iteration,
        )
        return AttemptResult.settled(PhaseOutcome.completed(gateRepairOutput))
      }
    }
    if (run.validationGateTriage) {
      return AttemptResult.settled(
        PhaseOutcome.completed(
          runLoop.collaborators.validationGateContinued1.gateTriageSegmentOutput(
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
    runLoop.collaborators.outputVerificationContinued2.persistVerifyFindingsCheckpointIfPresent(
      runLoop,
      run,
      args.captured.text,
    )
    val path = runLoop.collaborators.recordRejection.rejectionPath(error.reason)
    val reason = runLoop.collaborators.recordRejection.payloadFreeRejectionReason("phase-output-schema", path)
    val diagnosticWrite = runLoop.collaborators.attemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = run,
        iteration = args.iteration,
        rule = "phase-output-schema",
        reason = error.reason,
        captured = args.captured,
        targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(run, RejectedOutputTargetingOverrides(path = path)),
        ),
      ),
    )
    val repairEvidence = runLoop.collaborators.outputVerificationContinued3
      .structuralRepairEvidenceFromSchemaError(error)
    return runLoop.collaborators.outputPersistence.schemaInvalidAttempt(
      reason,
      args.fileManifest,
      malformedOutput = error.failureKind == FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED,
      retryReason = runLoop.collaborators.recordRejection.retryRejectionReason(reason, error.payloadFreeReason),
      correctiveRepairContext = runLoop.collaborators.attemptSettlement.correctiveRepairContextForRejection(
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
    val result = runLoop.collaborators.outputVerificationContinued1.completedPhaseRepositoryFingerprint(
      runLoop,
      run,
    ) ?: return RepositoryFingerprintResolution(null, null)
    if (!result.ok) {
      val blocked = AttemptResult.settled(
        runLoop.collaborators.phaseAttempts.blockInPhase(
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
}
