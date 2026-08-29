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


internal fun FeatureTaskRuntimeRunLoop.persistRejectedVerificationFindings(run: PhaseRun, verifyOutput: Map<String, Any?>) {
    if (!isGoalContinuationRun(run.request)) return
    val continuation = run.request.goalContinuation ?: return
    val reviewOutput = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.normalizedOutput?.envelope
      ?: return
    val reviewState = goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
    val passNumber = reviewState?.completedPassCount?.takeIf { it > 0 } ?: 1
    val recordedVerdicts = recorder.recordedFindingVerdicts(reviewOutput, run.request.dbPathOverride)
    val truncationRecords = mutableListOf<String>()
    val rejected = GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings(
      verifyOutput = verifyOutput,
      reviewOutput = reviewOutput,
      scope = UnaddressedFindingLedgerScope(
        issueKey = continuation.parentIssueKey,
        subtaskId = continuation.subtaskId,
        workflowId = run.request.workflowId,
        reviewPassNumber = passNumber,
      ),
      recordedVerdicts = recordedVerdicts,
      truncationRecords = truncationRecords,
    )
    truncationRecords.forEach { record ->
      runCatching { diagnostics.warning(record) }
    }
    if (rejected.isEmpty()) return
    recorder.appendRejectedVerificationFindings(
      workflowId = run.request.workflowId,
      passNumber = passNumber,
      rejected = rejected,
      dbOverride = run.request.dbPathOverride,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.persistStandaloneReviewCompletion(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome? {
    val persisted = try {
      recorder.recordCompletedPhase(
        phaseStateRequest(
          run,
          iteration,
          STATUS_COMPLETED,
          finished = true,
          outputArtifact = outputText,
          fileManifest = fileManifest,
          normalizedOutput = acceptedOutput.normalizedOutput,
          repairEvidence = acceptedOutput.repairEvidence,
          reviewRunId = state.recordFor(run.phaseId)?.reviewRunId,
        ),
        run.request.dbPathOverride,
      )
    } catch (error: RuntimeOwnedFactUnavailable) {
      return blockAndPersistInPhase(
        run,
        iteration,
        "Runtime-owned review settlement could not establish its persistence fact: ${error.message.orEmpty()}",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    return if (persisted) {
      null
    } else {
      blockAndPersistInPhase(
        run,
        iteration,
        "Runtime-owned review settlement could not be persisted.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
  }

internal fun FeatureTaskRuntimeRunLoop.persistGoalReviewCompletion(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome? {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
    val recordedVerdicts = recorder.recordedFindingVerdicts(outputMap, request.dbPathOverride)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
    val completed = runCatching {
      recorder.completeGoalReviewPhase(
        completion = GoalReviewPhaseCompletionRequest(
          phaseState = phaseStateRequest(
            run,
            iteration,
            STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
            fileManifest = fileManifest,
            normalizedOutput = normalizedOutput,
            repairEvidence = repairEvidence,
          ),
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = outputText,
          blockerDispositions = GoalSubtaskReviewSummaryReducer.blockerDispositions(
            outputMap,
            priorBlockerFindingIds(),
          ),
          commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
        ),
        dbOverride = run.request.dbPathOverride,
      )
    }.getOrElse { error ->
      return blockAndPersistInPhase(
        run,
        iteration,
        "Goal-subtask review could not atomically persist its pass and completed phase: " + error.message.orEmpty(),
        observability,
        fileManifest = fileManifest,
      )
    }
    return if (completed) {
      null
    } else {
      blockAndPersistInPhase(
        run,
        iteration,
        "Goal-subtask review could not atomically persist its reserved pass and completed phase.",
        observability,
        fileManifest = fileManifest,
      )
    }
  }

internal fun FeatureTaskRuntimeRunLoop.isGoalReviewRun(run: PhaseRun): Boolean =
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)

  // A goal-subtask review reserves its pass once in prepareGoalReviewRun, outside runPhaseAttempts, so a
  // bounded in-loop re-attempt reuses that same reserved pass instead of allocating another. Schema-invalid
  // output therefore earns the same fix-loop retries as every other phase: the reserved pass has no completed
  // output, which is the state a resume is already contracted to re-enter rather than treat as terminal.
internal fun FeatureTaskRuntimeRunLoop.schemaInvalidAttempt(
    operatorReason: String,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    malformedOutput: Boolean = false,
    retryReason: String = operatorReason,
    correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
  ): AttemptResult = AttemptResult.schemaInvalid(
    operatorReason = operatorReason,
    fileManifest = fileManifest,
    rejectedOutput = null,
    malformedOutput = malformedOutput,
    retryReason = retryReason,
    correctiveRepairContext = correctiveRepairContext,
  )

internal fun FeatureTaskRuntimeRunLoop.persistPhase(
    run: PhaseRun,
    iteration: Int,
    status: String,
    finished: Boolean,
    outputArtifact: String?,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    launched: LaunchedModelDirective? = null,
    reviewRunId: String? = null,
  ) {
    val phaseState =
      phaseStateRequest(
        run,
        iteration,
        status,
        finished,
        outputArtifact,
        fileManifest,
        launched = launched,
        reviewRunId = reviewRunId,
      )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordPhaseState(
      phaseState,
      run.request.dbPathOverride,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.phaseStateRequest(
    run: PhaseRun,
    iteration: Int,
    status: String,
    finished: Boolean,
    outputArtifact: String?,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
    repositoryFingerprint: String? = null,
    launched: LaunchedModelDirective? = null,
    reviewRunId: String? = null,
  ): FeatureTaskRuntimePhaseStateRequest {
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = status,
      attemptCount = iteration,
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = finished,
      outputArtifact = outputArtifact,
      normalizedOutput = normalizedOutput,
      repairEvidence = repairEvidence,
      repositoryFingerprint = repositoryFingerprint,
      fileManifestBefore = fileManifest?.before.orEmpty(),
      fileManifestAfter = fileManifest?.after.orEmpty(),
      fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
      loopId = run.reentry?.loopId,
      edgeIteration = run.reentry?.edgeIteration,
      reviewPassNumber = reviewPassNumber(run, state),
      auditScopeCriterionRefs = if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
        openAuditCriterionRefs()
      } else {
        emptyList()
      },
      launchedModel = launched?.modelOverride,
      launchedEffort = launched?.persistedEffort,
      launchOutcomeKnown = launched != null,
      reviewRunId = reviewRunId,
    )
  }

  /**
   * The model/effort the child is actually launched with. Cursor takes model and effort merged into
   * one bracketed `--model` argument, so its [persistedEffort] is null: the merged model already
   * carries the effort, and recording it twice would let the two drift apart.
   */
internal fun FeatureTaskRuntimeRunLoop.launchedModelDirective(run: PhaseRun): LaunchedModelDirective {
    val model = run.modelDirective?.model
    val effort = run.modelDirective?.effort
    if (run.resolvedAgent.resolvedAgentId == InstallAgent.CURSOR.id && model != null && effort != null) {
      return LaunchedModelDirective("$model[effort=$effort]", effort, persistedEffort = null)
    }
    return LaunchedModelDirective(model, effort, effort)
  }

internal fun FeatureTaskRuntimeRunLoop.reviewPassNumber(run: PhaseRun, state: FeatureTaskRuntimeRunState): Int? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    val durable = goalReviewStateOrNull() ?: return 1
    return resolveReviewPassNumber(
      reservedPassNumber = durable.reservedPassNumber ?: state.currentReviewPassNumber(),
      completedReviewPassCount = durable.completedPassCount,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.prepareLaunch(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorCorrection: PriorAttemptCorrection?,
    durablyClosedCriterionRefs: List<String>,
    repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ): PreparedLaunch {
    val resolvedBranchRecord = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = run.declaration,
      runInvariants = run.request.runInvariants,
      recordedOutputs = state.outputs(),
      drivingVerdict = run.reentry?.drivingVerdict,
      reentryGapCriteria = emptyList(),
      priorGapMemory = priorGapMemoryFor(run, state),
      durablyClosedCriterionRefs = durablyClosedCriterionRefs,
      repairLedger = null,
      repositoryCheckpoint = repositoryCheckpoint,
      expectedRepositoryCheckpoint = (
        if (
          run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
          run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
        ) {
          repositoryCheckpoint?.fingerprint
        } else {
          run.reentry?.expectedRepositoryCheckpoint ?: repositoryCheckpoint?.fingerprint
        }
        )
        ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
      branchIdentity = resolvedBranch,
      baseBranch = resolvedBranchRecord?.baseBranch ?: "main",
      validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
      qualityGateSelection = qualityGateSelection(),
    ).copy(recordedFindingVerdicts = recordedFindingVerdictsForFixHandoff(run, state))
    recorder.validateHandoffDeclarations(handoff.projectionDeclarations)
    val sharedEvidence = resolveSharedReviewEvidence(run, repositoryCheckpoint)
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      run.request.workflowId,
      planningProjectionValidator,
      run.request.agentAddonSelection,
      sharedEvidence?.reference,
    )
    recorder.recordPhaseBriefing(
      run.request.workflowId,
      briefing,
      run.request.dbPathOverride,
      sharedEvidence?.measurement,
    )
    val passNumber = reviewPassNumber(run, state)
    val depthResolution = passNumber?.let { pass ->
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, pass)
    }
    val executedTier = RuntimeOwnedReviewMode.execute(
      depthResolution?.resolvedTier ?: run.request.runInvariants.codeReviewMode,
    )
    depthResolution?.let { resolution -> persistResolvedReviewTier(run, resolution) }
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      issueKey = run.request.issueKey,
      briefing = briefing,
      suppressDecomposition = isGoalContinuationRun(run.request),
      codeReviewMode = executedTier,
      reviewPassNumber = passNumber,
      goalSubtaskReviewInput = run.goalReviewInput,
      baselineUntrackedPaths = resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
      resolvedReviewTier = depthResolution?.let { executedTier },
      reviewDecidingRule = depthResolution?.decidingRule,
      repairLedger = handoff.repairLedger,
      priorReviewContext = null,
      priorSchemaFailure = priorCorrection?.schemaGateReason,
      priorTerminalFailure = priorCorrection?.retryableTerminalReason,
      priorFindingCoverage = priorCorrection?.findingCoverageReason,
      correctiveRepairContext = priorCorrection?.correctiveRepairContext,
      operatorBlockRetry = operatorBlockRetry
        ?.takeIf { it.phaseId == run.phaseId && !operatorBlockRetryCompleted },
      implementationContinuation = implementationContinuationFor(run),
      validationGateFindings = run.validationGateFindings,
      validationGateTriagePlan = run.validationGateTriagePlan,
      validationGateRepair = run.validationGateRepair,
      validationGateTriage = run.validationGateTriage,
      agentRunValidateFallback = run.agentRunValidateFallback,
      packCollectAllCommand = packCollectAllCommand(run),
      packBuildCommand = packBuildCommand(run),
    ) + verifyFindingsSpecIntentSection(run)
    return PreparedLaunch(briefing, prompt)
  }

