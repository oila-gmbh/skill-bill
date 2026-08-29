package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validateDispositionCoverage

internal fun FeatureTaskRuntimeRunLoop.verifyFindingsBoundaryContext(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): BoundaryBodyDeliveryDecision? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) {
    return BoundaryBodyDeliveryDecision.NotApplicable
  }
  val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
  if (dispositions.isEmpty()) return BoundaryBodyDeliveryDecision.NotApplicable
  if (validateDispositionCoverage(dispositions, reviewFindingIdsForVerification()) != null) {
    return BoundaryBodyDeliveryDecision.NotApplicable
  }
  return null
}

internal fun FeatureTaskRuntimeRunLoop.verifyFindingsBoundaryValidationFailure(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
): BoundaryBodyDeliveryDecision? {
  val memory = phaseGates.findingVerificationBoundaryMemory
  memory.validateDispositionBoundaryContext(sections, dispositions)?.let {
    return BoundaryBodyDeliveryDecision.RejectDecision.of(it)
  }
  memory.validateDispositionBoundaryProvenance(sections, dispositions)?.let {
    return BoundaryBodyDeliveryDecision.RejectDecision.of(it)
  }
  return null
}

internal fun FeatureTaskRuntimeRunLoop.verifyFindingsDispositionGateContext(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): List<FeatureTaskRuntimeFindingVerificationDisposition>? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return null
  val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
  if (dispositions.isEmpty()) return null
  if (validateDispositionCoverage(dispositions, reviewFindingIdsForVerification()) != null) return null
  return dispositions
}

internal fun FeatureTaskRuntimeRunLoop.verifyFindingsDispositionGateValidationFailure(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
): String? {
  val memory = phaseGates.findingVerificationBoundaryMemory
  memory.validateDispositionBoundaryContext(sections, dispositions)?.let { return it }
  memory.validateDispositionBoundaryProvenance(sections, dispositions)?.let { return it }
  return null
}

internal fun FeatureTaskRuntimeRunLoop.validationGatePersistedAttempt(
  run: PhaseRun,
  iteration: Int,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  outputText: String,
): AttemptResult = AttemptResult.settled(
  PhaseOutcome.completed(
    FeatureTaskRuntimePhaseOutput(
      run.phaseId,
      iteration,
      outputText,
      normalizedOutput,
      repairEvidence,
    ),
  ),
)

internal fun FeatureTaskRuntimeRunLoop.persistStandardAcceptedOutput(
  args: PersistStandardAcceptedOutputArgs,
): AttemptResult? {
  val accepted = args.accepted
  val run = accepted.run
  val iteration = accepted.iteration
  val normalizedOutput = accepted.normalizedOutput
  val repairEvidence = accepted.repairEvidence
  val observability = accepted.observability
  val fileManifest = accepted.fileManifest
  val repositoryFingerprint = accepted.repositoryFingerprint
  val outputText = args.outputText
  if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) {
    persistRejectedVerificationFindings(run, normalizedOutput.envelope)
  }
  val persisted = recorder.recordCompletedPhase(
    phaseStateRequest(
      PhaseStateRequestArgs(
        write = PhaseStateWriteArgs(
          run = run,
          iteration = iteration,
          status = STATUS_COMPLETED,
          finished = true,
          outputArtifact = outputText,
        ),
        extras = PhaseStateRequestExtras(
          fileManifest = fileManifest,
          normalizedOutput = normalizedOutput,
          repairEvidence = repairEvidence,
          repositoryFingerprint = repositoryFingerprint,
        ),
      ),
    ),
    run.request.dbPathOverride,
  )
  if (!persisted) {
    return AttemptResult.settled(
      blockInPhase(
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Validated phase output could not be persisted to the authoritative workflow record.",
          observability = observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      ),
    )
  }
  return null
}

internal fun FeatureTaskRuntimeRunLoop.completedAttemptResult(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
): AttemptResult = AttemptResult.settled(
  PhaseOutcome.completed(
    FeatureTaskRuntimePhaseOutput(
      run.phaseId,
      iteration,
      outputText,
      normalizedOutput,
      repairEvidence,
    ),
  ),
)
