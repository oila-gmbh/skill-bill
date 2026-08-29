package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.toProjectionPayload
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewFindingVerdict
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.boundPriorGapNotes
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.application.review.model.DiffResolutionException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.time.Instant
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.StackDetectionException
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.application.review.model.UsageValidationException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus


internal fun FeatureTaskRuntimeRunLoop.blockAndPersist(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    loopId: String? = null,
    edgeIteration: Int? = null,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    outputArtifact: String? = null,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
    rejectedOutput: String? = null,
    childNeverLaunched: Boolean = false,
  ): PhaseOutcome {
    val phaseState = FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attemptCount.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      outputArtifact = normalizedOutput?.canonicalJson
        ?: outputArtifact
        ?: state.outputFor(run.phaseId)?.payload,
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
      reviewPassNumber = reviewPassNumber(run, state),
      // A launch that never produced a child clears the running write's stamp; every other block
      // reason happened around a child that did run, so its recorded model carries forward.
      launchOutcomeKnown = childNeverLaunched,
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordPhaseState(
      phaseState,
      run.request.dbPathOverride,
    )
    observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
    return PhaseOutcome.blocked(reason)
  }

  /**
   * Settles a phase whose launch was refused by the provider at a usage limit. The durable record is
   * PAUSED with a RETRYABLE disposition — the condition clears on the provider's clock, so resume
   * relaunches the phase — and the attempt is charged to the process-failure axis, never to the
   * semantic repair budget: a refused launch produced no output to repair.
   */
internal fun FeatureTaskRuntimeRunLoop.pauseAndPersistInPhase(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest?,
  ): PhaseOutcome {
    val attempt = attemptCount.coerceAtLeast(1)
    if (isGoalContinuationRun(request)) {
      goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = request.workflowId,
          workflowStatus = STATUS_PAUSED,
        ),
        dbOverride = request.dbPathOverride,
      )
    }
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_PAUSED,
        attemptCount = attempt,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        outputArtifact = state.outputFor(run.phaseId)?.payload,
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
    pauseAt(run.phaseId, reason, run.phaseId)
    return PhaseOutcome.paused(reason)
  }

internal fun FeatureTaskRuntimeRunLoop.blockAndPersistInPhase(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    outputArtifact: String? = null,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
    rejectedOutput: String? = null,
    childNeverLaunched: Boolean = false,
  ): PhaseOutcome = blockAndPersist(
    run,
    attemptCount,
    reason,
    observability,
    loopId = run.reentry?.loopId,
    edgeIteration = run.reentry?.edgeIteration,
    failureDisposition = failureDisposition,
    fileManifest = fileManifest,
    outputArtifact = outputArtifact,
    normalizedOutput = normalizedOutput,
    repairEvidence = repairEvidence,
    rejectedOutput = rejectedOutput,
    childNeverLaunched = childNeverLaunched,
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
internal fun FeatureTaskRuntimeRunLoop.settleRecordRejection(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    rejection: RecordRejection,
  ): PhaseOutcome {
    val consumer = run.phaseId
    val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[consumer]
    val edge = producer?.let { candidate ->
      transitions.backwardEdges.firstOrNull {
        it.fromPhaseId == consumer && it.destinationPhaseId == candidate &&
          it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED
      }
    }
    if (producer == null || edge == null || producer !in transitions.forwardPhaseIds) {
      return blockUnattributableRecordRejection(
        run,
        state,
        iteration,
        observability,
        rejection,
        producer,
      )
    }
    val rejectedRecord = state.outputFor(producer)
    val producingIteration =
      (rejectedRecord?.iteration ?: state.recordFor(producer)?.attemptCount ?: 1).coerceAtLeast(1)
    val producerAgentId = state.recordFor(producer)?.resolvedAgentId
      ?: return blockAndPersistInPhase(
        run,
        iteration,
        "Feature-task-runtime phase '$consumer' rejected the durable record produced by '$producer', but the " +
          "producing phase's resolved agent is unavailable, so exact raw evidence cannot be scoped to a " +
          "producer. The run blocks instead of fabricating a rejected-output diagnostic.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        childNeverLaunched = true,
      )
    val producerEvidence = when (
      val producerRead = recorder.producerOutput(
        request.workflowId,
        producer,
        producingIteration,
        producerAgentId,
        request.dbPathOverride,
        state.evidenceGeneration(producer),
      )
    ) {
      is FeatureTaskRuntimeProducerOutputRead.Found -> producerRead.evidence
      is FeatureTaskRuntimeProducerOutputRead.Absent,
      is FeatureTaskRuntimeProducerOutputRead.Unreadable,
      -> {
        val evidenceClause = if (producerRead is FeatureTaskRuntimeProducerOutputRead.Unreadable) {
          "retained evidence for attempt $producingIteration exists and the diagnostic store refused it " +
            "(${producerRead.failureClass.wireValue}). The run blocks instead of fabricating a " +
            "rejected-output diagnostic from normalized workflow state."
        } else {
          "no retained evidence exists for attempt $producingIteration. The run blocks instead of fabricating " +
            "a rejected-output diagnostic from normalized workflow state."
        }
        return blockAndPersistInPhase(
          run,
          iteration,
          "Feature-task-runtime phase '$consumer' rejected the durable record produced by '$producer', but " +
            evidenceClause,
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          childNeverLaunched = true,
        )
      }
    }
    val rejectedPayload = producerEvidence.payload ?: byteArrayOf()
    val diagnosticWrite = recordRejectedOutput(
      run = run,
      iteration = producingIteration,
      rule = "reconciliation-${rejection.rejectionClass}",
      reason = retryRejectionReason(
        payloadFreeRejectionReason(
          "reconciliation-${rejection.rejectionClass}",
          rejectionPath(rejection.rejectionDetail),
        ),
        rejection.rejectionDetail,
      ),
      outputBytes = rejectedPayload,
      phaseId = producer,
      agentId = producerEvidence.agentId,
      model = producerEvidence.model,
      path = rejectionPath(rejection.rejectionDetail),
      outputByteSize = producerEvidence.byteSize,
      outputSha256 = producerEvidence.sha256,
      outputTruncated = producerEvidence.payload == null,
      // The diagnostic names the exact capture that was rejected, which is the turn the read resolved.
      repairTurn = producerEvidence.repairTurn,
    )
    val regenerationAttempt = (state.edgeIterationCount(edge.loopId) + 1).coerceAtLeast(1)
    recorder.appendQuarantineEntry(
      request.workflowId,
      FeatureTaskRuntimeQuarantineEntry(
        producingPhaseId = producer,
        consumingPhaseId = consumer,
        producingIteration = producingIteration,
        rejectionClass = rejection.rejectionClass,
        rejectionDetail = payloadFreeRejectionReason(
          "reconciliation-${rejection.rejectionClass}",
          rejectionPath(rejection.rejectionDetail),
        ),
        regenerationAttempt = regenerationAttempt,
        quarantinedAtIteration = iteration.coerceAtLeast(1),
        diagnosticIdentity = (diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.identity,
        rejectedRecordByteSize = producerEvidence.byteSize,
        rejectedRecordSha256 = producerEvidence.sha256,
        diagnosticDegraded = diagnosticWrite is FeatureTaskRuntimeRejectedOutputWrite.Degraded,
      ),
      request.dbPathOverride,
    )
    return PhaseOutcome.regenerateProducer(producer)
  }

