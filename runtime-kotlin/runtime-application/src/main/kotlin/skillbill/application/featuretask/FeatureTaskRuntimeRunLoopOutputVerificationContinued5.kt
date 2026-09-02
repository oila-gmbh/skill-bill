package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

@Inject
class FeatureTaskRuntimeRunLoopOutputVerificationContinued5 {
  fun verifyFindingsDispositionGateValidationFailure(
    runLoop: FeatureTaskRuntimeRunLoop,
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): String? {
    val memory = runLoop.phaseGates.findingVerificationBoundaryMemory
    memory.validateDispositionBoundaryContext(sections, dispositions)?.let { return it }
    memory.validateDispositionBoundaryProvenance(sections, dispositions)?.let { return it }
    return null
  }

  internal fun validationGatePersistedAttempt(
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

  internal fun persistStandardAcceptedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
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
      runLoop.collaborators.outputPersistence.persistRejectedVerificationFindings(
        runLoop,
        run,
        normalizedOutput.envelope,
      )
    }
    val persisted = runLoop.recorder.recordCompletedPhase(
      runLoop.collaborators.outputPersistence.phaseStateRequest(
        runLoop,
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
        runLoop.collaborators.phaseAttempts.blockInPhase(
          runLoop,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = "Validated phase output could not be persisted to the authoritative workflow record.",
            observability = runLoop.observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          ),
        ),
      )
    }
    return null
  }

  internal fun completedAttemptResult(
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
}
