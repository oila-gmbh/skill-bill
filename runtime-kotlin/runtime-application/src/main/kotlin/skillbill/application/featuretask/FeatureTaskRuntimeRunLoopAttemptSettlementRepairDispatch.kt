package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

@Inject
class FeatureTaskRuntimeRunLoopAttemptSettlementRepairDispatch {
  internal fun settleValidatedOutputBoundary(
    runLoop: FeatureTaskRuntimeRunLoop,
    capture: ValidatedOutputCapture,
    outputMap: Map<String, Any?>,
    reject: (String, String) -> AttemptResult,
  ): AttemptResult? {
    val bodyDelivery = runLoop.collaborators.outputVerificationContinued2
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
    runLoop.collaborators.outputVerificationContinued1.auditGapProgressPause(
      runLoop,
      run,
      outputMap,
      repositoryFingerprint,
      attested.canonicalJson,
    )?.let { pause ->
      runLoop.collaborators.planningBranch.mintAuditGapPause(runLoop, pause, run.phaseId, attested.canonicalJson)
      return AttemptResult.settled(PhaseOutcome.paused(pause.reason))
    }
    terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
      return runLoop.collaborators.outputVerificationContinued1.terminalOutputAttempt(
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
    val finalisation = runLoop.collaborators.attemptSettlementContinued3.finaliseSubtaskCommit(
      runLoop,
      run,
      attested,
    )
  ) {
    is CommitPushNotApplicable -> attested to null
    is CommitPushSettled -> finalisation.output to null
    is CommitPushBlocked -> attested to AttemptResult.settled(
      runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
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
    runLoop.collaborators.outputVerification.completionProjectionRejection(
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
    runLoop.collaborators.repairReceipt.settleCompletedImplementationOutput(
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
    runLoop.collaborators.attemptSettlementContinued3.retainSettledProducerOutput(runLoop, capture)
    return runLoop.collaborators.outputVerificationContinued3.persistAcceptedOutput(
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
}
