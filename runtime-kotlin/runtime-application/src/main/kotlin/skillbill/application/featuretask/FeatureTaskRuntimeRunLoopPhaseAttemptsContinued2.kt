package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

@Inject
class FeatureTaskRuntimeRunLoopPhaseAttemptsContinued2 {
  internal fun recordIncompleteAttempt(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    attempt: AttemptResult,
  ): Boolean {
    val normalized = attempt.incompleteWorkOutput ?: return false
    return runLoop.recorder.recordIncompleteImplementationAttempt(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_RUNNING,
        attemptCount = iteration.coerceAtLeast(1),
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        normalizedOutput = normalized,
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
      ),
      run.request.dbPathOverride,
    )
  }

  internal fun blockAndPersist(runLoop: FeatureTaskRuntimeRunLoop, args: BlockAndPersistArgs): PhaseOutcome {
    val run = args.run
    val attemptCount = args.attemptCount
    val reason = args.reason
    val observability = args.observability
    val loopId = args.loopId
    val edgeIteration = args.edgeIteration
    val failureDisposition = args.failureDisposition
    val fileManifest = args.payload.fileManifest
    val outputArtifact = args.payload.outputArtifact
    val normalizedOutput = args.payload.normalizedOutput
    val repairEvidence = args.payload.repairEvidence
    val rejectedOutput = args.payload.rejectedOutput
    val childNeverLaunched = args.payload.childNeverLaunched
    val phaseState = FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attemptCount.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      outputArtifact = normalizedOutput?.canonicalJson
        ?: outputArtifact
        ?: runLoop.state.outputFor(run.phaseId)?.payload,
      rejectedOutput = rejectedOutput,
      normalizedOutput = normalizedOutput,
      repairEvidence = repairEvidence,
      blockedReason = reason,
      failureDisposition = failureDisposition,
      fileManifestBefore = fileManifest?.before.orEmpty(),
      fileManifestAfter = fileManifest?.after.orEmpty(),
      fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
      loopId = loopId,
      edgeIteration = edgeIteration,
      reviewPassNumber = runLoop.collaborators.outputPersistence.reviewPassNumber(runLoop, run, runLoop.state),
      // A launch that never produced a child clears the running write's stamp; every other block
      // reason happened around a child that did run, so its recorded model carries forward.
      launchOutcomeKnown = childNeverLaunched,
    )
    runLoop.state.reserveReviewPass(phaseState.reviewPassNumber)
    runLoop.recorder.recordPhaseState(
      phaseState,
      run.request.dbPathOverride,
    )
    runLoop.observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
    return PhaseOutcome.blocked(reason)
  }

  /**
   * Settles a phase whose launch was refused by the provider at a usage limit. The durable record is
   * PAUSED with a RETRYABLE disposition — the condition clears on the provider's runLoop.clock, so resume
   * relaunches the phase — and the attempt is charged to the process-failure axis, never to the
   * semantic repair budget: a refused launch produced no output to repair.
   */
  internal fun pauseAndPersistInPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PauseAndPersistInPhaseArgs,
  ): PhaseOutcome {
    val run = args.run
    val attemptCount = args.attemptCount
    val reason = args.reason
    val observability = args.observability
    val fileManifest = args.fileManifest
    val attempt = attemptCount.coerceAtLeast(1)
    if (isGoalContinuationRun(runLoop.request)) {
      runLoop.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = runLoop.request.workflowId,
          workflowStatus = STATUS_PAUSED,
        ),
        dbOverride = runLoop.request.dbPathOverride,
      )
    }
    runLoop.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_PAUSED,
        attemptCount = attempt,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        outputArtifact = runLoop.state.outputFor(run.phaseId)?.payload,
        blockedReason = reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.RETRYABLE,
        fileManifestBefore = fileManifest?.before.orEmpty(),
        fileManifestAfter = fileManifest?.after.orEmpty(),
        fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
        // A provider-limit refusal is reported by a child that did spawn and run under the launched
        // model, so the running write's stamp is kept: "which model hit the usage limit" is the
        // operative diagnostic question on a limit pause.
        launchOutcomeKnown = false,
      ),
      run.request.dbPathOverride,
    )
    observability.paused(run.phaseId, run.resolvedAgent.resolvedAgentId, attempt, reason)
    runLoop.collaborators.planningBranch.pauseAt(runLoop, run.phaseId, reason, run.phaseId)
    return PhaseOutcome.paused(reason)
  }

  internal fun blockAndPersistInPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: BlockAndPersistInPhaseArgs,
  ): PhaseOutcome = blockAndPersist(
    runLoop,
    BlockAndPersistArgs(
      run = args.run,
      attemptCount = args.attemptCount,
      reason = args.reason,
      observability = args.observability,
      loopId = args.run.reentry?.loopId,
      edgeIteration = args.run.reentry?.edgeIteration,
      failureDisposition = args.failureDisposition,
      payload = args.payload,
    ),
  )

  /**
   * SKILL-140: a consumer's launch seam rejected an upstream producer's durable record. Quarantine the
   * rejected record as private evidence and settle the consumer with the RECORD_REJECTED verdict so the
   * existing transition machinery re-enters the producing phase under its bounded regeneration cap. A
   * record with no attributable producer, or whose producer the resolved pipeline dropped, blocks
   * durably with an actionable reason instead of attempting an impossible re-entry.
   *
   * A record rejection is raised at the launch seam, before any child is spawned, so every block
   * seam reachable from here — including [blockUnattributableRecordRejection] — settles a phase
   * whose child provably never ran and clears the running write's model stamp.
   */
  internal fun settleRecordRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleRecordRejectionArgs,
  ): PhaseOutcome {
    val run = args.run
    val state = args.state
    val iteration = args.iteration
    val observability = args.observability
    val rejection = args.rejection
    val regeneration = runLoop.collaborators.phaseAttemptsContinued3.recordRejectionRegenerationEdge(
      runLoop,
      run.phaseId,
    )
    if (regeneration == null) {
      return runLoop.collaborators.recordRejection.blockUnattributableRecordRejection(
        runLoop,
        UnattributableRecordRejectionArgs(
          context = PhaseAttemptContext(run, state, iteration, observability),
          rejection = rejection,
          producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[run.phaseId],
        ),
      )
    }
    val attemptContext = PhaseAttemptContext(run, state, iteration, observability)
    val evidenceResolution = runLoop.collaborators.phaseAttemptsContinued3
      .readProducerEvidenceForRecordRejection(
        runLoop,
        ProducerEvidenceRecordRejectionArgs(
          context = attemptContext,
          producer = regeneration.producer,
          consumer = run.phaseId,
        ),
      )
    return when (evidenceResolution) {
      is FeatureTaskRuntimeRunLoopPhaseAttemptsContinued3
        .RecordRejectionEvidenceResolution.Settled,
      -> evidenceResolution.outcome
      is FeatureTaskRuntimeRunLoopPhaseAttemptsContinued3.RecordRejectionEvidenceResolution.Ready ->
        runLoop.collaborators.phaseAttemptsContinued3.quarantineRecordRejection(
          runLoop,
          QuarantineRecordRejectionArgs(
            context = attemptContext,
            rejection = rejection,
            regeneration = regeneration,
            producerEvidence = evidenceResolution.evidence,
          ),
        )
    }
  }

  internal data class RecordRejectionRegenerationEdge(
    val producer: String,
    val edge: FeatureTaskRuntimeBackwardEdge,
  )
}
