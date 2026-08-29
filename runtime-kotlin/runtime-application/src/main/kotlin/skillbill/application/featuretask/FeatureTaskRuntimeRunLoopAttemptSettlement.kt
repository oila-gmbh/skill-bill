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


internal fun FeatureTaskRuntimeRunLoop.gateOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    outputBytes: ByteArray,
    outputTruncated: Boolean,
    outputByteSize: Long,
    outputSha256: String,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult {
    // Build/validate gate repair: the agent mutates the tree from runtime-parsed findings. Stdout is
    // not a phase receipt — the coordinator re-runs the pack command and mints build_receipt /
    // validation_receipt. Requiring schema here blocked honest fixes under the output-gate cap.
    if (run.validationGateRepairTurn > 0) {
      return AttemptResult.settled(
        PhaseOutcome.completed(gateRepairSegmentOutput(run, iteration)),
      )
    }
    if (run.validationGateTriage) {
      return AttemptResult.settled(
        PhaseOutcome.completed(gateTriageSegmentOutput(run, iteration, outputText)),
      )
    }
    return try {
      val acceptedOutput = outputValidator
        .validatePhaseOutput(outputText, sourceLabel = run.phaseId)
        .requireAcceptedOutput(run.phaseId)
      settleValidatedOutput(
        run, iteration, acceptedOutput.normalizedOutput, acceptedOutput.repairEvidence, observability, fileManifest,
        outputText, outputBytes, outputTruncated, outputByteSize, outputSha256,
      )
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      persistVerifyFindingsCheckpointIfPresent(run, outputText)
      val path = rejectionPath(error.reason)
      val reason = payloadFreeRejectionReason("phase-output-schema", path)
      val diagnosticWrite = recordRejectedOutput(
        run, iteration, "phase-output-schema", error.reason, outputBytes, path = path,
        outputTruncated = outputTruncated, outputByteSize = outputByteSize, outputSha256 = outputSha256,
      )
      val repairEvidence = structuralRepairEvidenceFromSchemaError(error)
      schemaInvalidAttempt(
        reason,
        fileManifest,
        malformedOutput = error.failureKind == FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED,
        retryReason = retryRejectionReason(reason, error.payloadFreeReason),
        correctiveRepairContext = correctiveRepairContextForRejection(
          run = run,
          iteration = iteration,
          outputText = outputText,
          outputTruncated = outputTruncated,
          outputByteSize = outputByteSize,
          outputSha256 = outputSha256,
          diagnosticWrite = diagnosticWrite,
          rejectionRule = "phase-output-schema",
          rejectionPath = path,
          payloadFreeConstraint = error.payloadFreeReason.orEmpty(),
          acceptedAfterStructuralRepair = error.acceptedAfterStructuralRepair,
          structuralRepairEvidence = repairEvidence,
        ),
      )
    }
  }

  /**
   * Builds the in-flight corrective-repair context from the same capture metadata and diagnostic
   * identity just recorded for this rejection. Truncated captures stay payload-free (digest/bytes
   * only); an unchanged body within budget can carry Exact for the authorized repair projection.
   */
internal fun FeatureTaskRuntimeRunLoop.correctiveRepairContextForRejection(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    outputTruncated: Boolean,
    outputByteSize: Long,
    outputSha256: String,
    diagnosticWrite: FeatureTaskRuntimeRejectedOutputWrite,
    rejectionRule: String,
    rejectionPath: String,
    payloadFreeConstraint: String,
    acceptedAfterStructuralRepair: Boolean = false,
    structuralRepairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? =
      null,
  ): FeatureTaskRuntimeCorrectiveRepairContext {
    val utf8ByteCount = outputByteSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val captured = if (outputTruncated) {
      // Truncated stdout is not the complete capture; digest/byte metadata still refer to the
      // observed stream, so never classify the retained excerpt as Exact.
      CorrectiveRepairCapturedResponse.AlreadyTruncated(
        utf8ByteCount = utf8ByteCount,
        digestSha256 = outputSha256,
      )
    } else {
      // Prefer the capture-boundary digest/byte metadata so Exact metadata matches the private
      // diagnostic row; classify still verifies they hash the framed body (loud-fail on drift).
      CorrectiveRepairCapturedResponse.classify(
        body = outputText,
        alreadyTruncated = false,
        knownUtf8ByteCount = utf8ByteCount,
        knownDigestSha256 = outputSha256,
      )
    }
    val repairEvidence = structuralRepairEvidence
    val locator = (diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.let {
      CorrectiveRepairDiagnosticLocator(it.identity)
    }
    val degradationClass = (diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Degraded)?.failureClass
    return FeatureTaskRuntimeCorrectiveRepairContext(
      phaseId = run.phaseId,
      attempt = iteration.coerceAtLeast(1),
      repairTurn = run.validationGateRepairTurn.takeIf { it > 0 },
      rejectionRule = rejectionRule,
      rejectionPath = rejectionPath,
      payloadFreeConstraint = payloadFreeConstraint,
      diagnosticLocator = locator,
      captured = captured,
      acceptedAfterStructuralRepair = acceptedAfterStructuralRepair || repairEvidence != null,
      structuralRepairEvidence = repairEvidence,
      diagnosticDegradationClass = degradationClass,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.recordRejectedOutput(
    run: PhaseRun,
    iteration: Int,
    rule: String,
    reason: String,
    outputBytes: ByteArray,
    phaseId: String = run.phaseId,
    agentId: String = run.resolvedAgent.resolvedAgentId,
    model: String = run.modelDirective?.model ?: "unspecified",
    path: String = "/",
    outputTruncated: Boolean = false,
    outputByteSize: Long = outputBytes.size.toLong(),
    outputSha256: String = RejectedOutputDiagnosticService.sha256(outputBytes),
    // A repair turn belongs to the phase this run is executing. A rejection attributed to some other
    // producer phase is that producer's own capture, so it stays at turn 0 unless the caller knows
    // otherwise from the producer's retained evidence.
    repairTurn: Int = if (phaseId == run.phaseId) run.validationGateRepairTurn else 0,
  ): FeatureTaskRuntimeRejectedOutputWrite = recorder.recordRejectedOutput(
    RejectedOutputDiagnosticRequest(
      workflowId = run.request.workflowId,
      phaseId = phaseId,
      attempt = iteration.coerceAtLeast(1),
      rule = rule,
      path = path,
      reason = reason,
      agentId = agentId,
      model = model,
      rawResponse = outputBytes,
      observedByteSize = outputByteSize,
      observedSha256 = outputSha256,
      truncated = outputTruncated,
      repairTurn = repairTurn,
    ),
    run.request.dbPathOverride,
    state.evidenceGeneration(phaseId),
  )

  /**
   * Stores what the child wrote before it died, under rule
   * [FEATURE_TASK_RUNTIME_PROCESS_FAILURE_RULE].
   *
   * A process failure reaches no output gate, so without this the run keeps only the bounded excerpt
   * inlined in the block reason. That was enough to report a failure and never enough to diagnose
   * one: a phase could die four times over reporting a cause its own output would have contradicted,
   * with no artifact to check it against.
   *
   * Best-effort by construction. The block that follows is what actually settles the phase, so a
   * diagnostic that cannot be written must never take the settle down with it — a lost artifact is a
   * worse diagnosis, while a lost block is a wedged run. Absent output writes nothing rather than an
   * empty row, so a stored diagnostic always means the child really did say something.
   */
internal fun FeatureTaskRuntimeRunLoop.persistChildProcessFailureOutput(
    run: PhaseRun,
    iteration: Int,
    reason: String,
    childOutput: FeatureTaskRuntimeChildOutput?,
  ) {
    val output = childOutput ?: return
    runCatching {
      recordRejectedOutput(
        run = run,
        iteration = iteration,
        rule = FEATURE_TASK_RUNTIME_PROCESS_FAILURE_RULE,
        reason = boundedSchemaGateDetail(reason),
        outputBytes = output.storedBody().encodeToByteArray(),
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

  // Complexity here is the settle decision table itself: one branch per phase-output disposition.
  // Splitting it would scatter a single contract across helpers without removing a branch.
internal fun FeatureTaskRuntimeRunLoop.settleValidatedOutput(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    outputText: String,
    outputBytes: ByteArray,
    outputTruncated: Boolean,
    outputByteSize: Long,
    outputSha256: String,
  ): AttemptResult {
    // Absent-gate validate: agents are told not to invent gate_run_count/gate_runs, but the
    // validation_receipt consumer projection requires them. Attest measured-absent counts here so
    // the first completed attempt satisfies write_history without burning a fix-loop retry.
    val attested = attestAbsentGateValidationReceipt(run, normalizedOutput)
    val outputMap = attested.envelope
    val capture = ValidatedOutputCapture(
      run = run,
      iteration = iteration,
      outputText = outputText,
      outputBytes = outputBytes,
      outputTruncated = outputTruncated,
      outputByteSize = outputByteSize,
      outputSha256 = outputSha256,
      repairEvidence = repairEvidence,
      fileManifest = fileManifest,
    )
    fun reject(rule: String, detail: String): AttemptResult = rejectValidatedOutput(capture, outputMap, rule, detail)
    when (val bodyDelivery = findingVerificationBoundaryBodyDeliveryDecision(run, outputMap)) {
      is BoundaryBodyDeliveryDecision.RejectDecision -> return reject("output-verification", bodyDelivery.reason)
      is BoundaryBodyDeliveryDecision.ContinueDecision ->
        return AttemptResult.boundaryBodyDelivery(bodyDelivery.reason, fileManifest)
      BoundaryBodyDeliveryDecision.NotApplicable -> Unit
    }
    firstValidatedOutputRejection(run.phaseId, outputMap)?.let { (rule, reason) ->
      return reject(rule, reason)
    }
    val repositoryFingerprint = completedPhaseRepositoryFingerprint(run)?.let { result ->
      if (!result.ok) {
        return AttemptResult.settled(
          blockAndPersistInPhase(
            run,
            iteration,
            "Completed-phase repository fingerprinting failed for '${run.phaseId}': ${result.error}",
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
            fileManifest = fileManifest,
          ),
        )
      }
      result.value
    }
    auditGapProgressPause(run, outputMap, repositoryFingerprint, attested.canonicalJson)?.let { pause ->
      mintAuditGapPause(pause, run.phaseId, attested.canonicalJson)
      return AttemptResult.settled(PhaseOutcome.paused(pause.reason))
    }
    terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
      return terminalOutputAttempt(
        run,
        iteration,
        reason,
        outputMap,
        attested,
        repairEvidence,
        observability,
        fileManifest,
      )
    }
    // Placed after the terminal path so a blocked or failed envelope never reaches it: only a phase
    // claiming 'completed' owes the projection its consumer will parse.
    completionProjectionRejection(
      run,
      iteration,
      outputMap,
      attested,
      repairEvidence,
      repositoryFingerprint,
    )?.let { (rule, reason) -> return reject(rule, reason) }
    // Deliberately LAST of the gates: a receipt that both under-closes its plan tasks and carries a
    // real projection, reconciliation-report or output-verification defect is a structural failure
    // first. Evaluating incompleteness ahead of those gates routed such a document into the
    // continuation loop, where priorSchemaFailure stays null, so the repairable contract defect was
    // never named to the agent and the run burned every continuation segment before blocking. Running
    // last means the continuation path only ever sees a receipt that already satisfies its contract.
    // Returned directly rather than through reject(): semantic incompleteness is not a rejected
    // output and must never be recorded or budgeted as one. Blocked/failed envelopes and
    // decomposition packages still bypass it, via the terminal path and the producer gate above.
    settleCompletedImplementationOutput(
      run,
      outputMap,
      ::reject,
      iteration,
      observability,
      fileManifest,
    )?.let { return it }
    val finalised = when (val finalisation = finaliseSubtaskCommit(run, attested)) {
      is CommitPushNotApplicable -> attested
      is CommitPushSettled -> finalisation.output
      is CommitPushBlocked -> return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          finalisation.reason,
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          fileManifest = fileManifest,
        ),
      )
    }
    retainSettledProducerOutput(capture)
    return persistAcceptedOutput(
      run,
      iteration,
      finalised,
      repairEvidence,
      observability,
      fileManifest,
      repositoryFingerprint,
    )
  }

