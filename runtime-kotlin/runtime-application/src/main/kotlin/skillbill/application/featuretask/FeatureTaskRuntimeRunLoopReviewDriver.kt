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


internal fun FeatureTaskRuntimeRunLoop.prepareRuntimeOwnedReview(run: PhaseRun, state: FeatureTaskRuntimeRunState): RuntimeOwnedReviewPrep {
    val input = run.goalReviewInput
      ?: return RuntimeOwnedReviewBlocked(
        PhaseOutcome.blocked("Runtime-owned review is missing the child-owned review input."),
      )
    val iteration = state.nextIteration(run.phaseId)
    val passNumber = reviewPassNumber(run, state) ?: 1
    val pinnedMode = run.request.runInvariants.codeReviewMode
    val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(pinnedMode, passNumber)
    val durableRecord = state.recordFor(run.phaseId)
    val reviewRunId = durableRecord
      ?.takeIf { (it.reviewPassNumber ?: 1) == passNumber }
      ?.reviewRunId
      ?.takeIf(String::isNotBlank)
      ?: FeatureTaskRuntimeReviewEnvelope.mintReviewRunId()
    persistPhase(
      run,
      iteration,
      STATUS_RUNNING,
      finished = false,
      outputArtifact = null,
      reviewRunId = reviewRunId,
    )
    val checkpoint = gitOperations.repositoryFingerprint(run.request.repoRoot).value
      .takeIf(String::isNotBlank)
      ?: return RuntimeOwnedReviewBlocked(
        PhaseOutcome.blocked(
          "Runtime-owned review could not resolve a repository checkpoint fingerprint.",
        ),
      )
    return RuntimeOwnedReviewReady(
      run = run,
      launch = RuntimeOwnedReviewLaunch(
        iteration = iteration,
        passNumber = passNumber,
        resolvedTier = RuntimeOwnedReviewMode.execute(resolution.resolvedTier),
        reviewRunId = reviewRunId,
        checkpoint = checkpoint,
      ),
      driverRequest = FeatureTaskRuntimeReviewDriverMapper.request(
        input = input,
        runInvariants = run.request.runInvariants,
        agents = FeatureTaskRuntimeReviewDriverAgents(
          agent1Id = run.resolvedAgent.resolvedAgentId,
        ),
        pass = FeatureTaskRuntimeReviewDriverPass(
          passNumber = passNumber,
          pinnedMode = pinnedMode,
          reviewRunId = reviewRunId,
        ),
        workspace = FeatureTaskRuntimeReviewDriverWorkspace(
          repoRoot = run.request.repoRoot,
          timeout = run.request.timeout,
          agentAddonSelection = run.request.agentAddonSelection,
          baselineUntrackedPaths = reviewBaselineUntrackedPaths(run),
        ),
      ),
    )
  }

internal fun FeatureTaskRuntimeRunLoop.executePreparedReviewDriver(
    prepared: RuntimeOwnedReviewReady,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val run = prepared.run
    observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      prepared.launch.iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
    val before = gitOperations.worktreeStatus(run.request.repoRoot)
    if (!before.ok) {
      return blockAndPersistInPhase(
        run,
        prepared.launch.iteration,
        "Feature-task-runtime phase 'review' could not capture its before-file manifest: ${before.error}",
        observability,
      )
    }
    return when (val attempt = invokeReviewDriver(prepared.driverRequest)) {
      is ReviewDriverFailed -> blockAndPersistInPhase(
        run,
        prepared.launch.iteration,
        attempt.reason,
        observability,
        failureDisposition = attempt.disposition,
      )
      is ReviewDriverReady -> {
        val after = gitOperations.worktreeStatus(run.request.repoRoot)
        if (!after.ok) {
          return blockAndPersistInPhase(
            run,
            prepared.launch.iteration,
            "Feature-task-runtime phase 'review' could not capture its after-file manifest: ${after.error}",
            observability,
          )
        }
        capturePhaseContentIdentities(run.phaseId)
        settleReviewDriverResult(
          prepared,
          attempt.result,
          observability,
          FeatureTaskRuntimePhaseFileManifest(
            before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.value.orEmpty()),
            after = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value.orEmpty()),
          ),
        )
      }
    }
  }

internal fun FeatureTaskRuntimeRunLoop.invokeReviewDriver(request: ParallelCodeReviewRequest): ReviewDriverAttempt = try {
    ReviewDriverReady(phaseGates.reviewDriver.run(request))
  } catch (error: DiffResolutionException) {
    ReviewDriverFailed(
      "Runtime-owned review could not resolve the child-owned diff: ${error.message.orEmpty()}",
    )
  } catch (error: UsageValidationException) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  } catch (error: StackDetectionException) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  } catch (error: ReviewContextBudgetExceededException) {
    ReviewDriverFailed(
      "Runtime-owned review exceeded a review-context budget: ${error.message.orEmpty()}",
    )
  } catch (error: UnreadableSpecIntentProjectionError) {
    ReviewDriverFailed(
      "Runtime-owned review could not read the spec intent projection: ${error.message.orEmpty()}",
    )
  } catch (error: InvalidReviewContextSchemaError) {
    ReviewDriverFailed(
      "Runtime-owned review produced an invalid review-context envelope: ${error.message.orEmpty()}",
    )
  } catch (error: RuntimeOwnedFactUnavailable) {
    ReviewDriverFailed(
      "Runtime-owned review could not establish a required persistence fact: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
    )
  } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error::class.simpleName}: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.settleReviewDriverResult(
    prepared: RuntimeOwnedReviewReady,
    result: ParallelCodeReviewResult,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome {
    val run = prepared.run
    failedReviewLaneReason(result)?.let { reason ->
      return blockAndPersistInPhase(
        run,
        prepared.launch.iteration,
        reason,
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
    }
    val cycle = FeatureTaskRuntimeReviewDriverCycle.assemble(
      result = result,
      request = prepared.driverRequest,
      cycle = FeatureTaskRuntimeReviewCycleContext(
        passNumber = prepared.launch.passNumber,
        resolvedTier = prepared.launch.resolvedTier,
        repositoryFingerprint = prepared.launch.checkpoint,
        blockerDispositions = reviewBlockerDispositions(
          run,
          prepared.launch.passNumber,
          result,
          prepared.launch.reviewRunId,
          prepared.launch.resolvedTier,
        ),
      ),
    )
    return settleRuntimeOwnedReview(run, prepared.launch.iteration, cycle.outputText, observability, fileManifest)
  }

internal fun FeatureTaskRuntimeRunLoop.reviewBaselineUntrackedPaths(run: PhaseRun): List<String> =
    recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
      ?.baselineUntrackedPaths
      ?.takeIf { it.isNotEmpty() }
      ?: goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
        ?.baselineUntrackedPaths
        .orEmpty()

internal fun FeatureTaskRuntimeRunLoop.failedReviewLaneReason(result: ParallelCodeReviewResult): String? {
    val parent = result.lane1
    if (parent.agentId.isBlank() || parent.success) return null
    val detail = parent.failureReason?.takeIf(String::isNotBlank) ?: "lane failed"
    return "Feature-task-runtime phase 'review' $detail"
  }

internal fun FeatureTaskRuntimeRunLoop.reviewBlockerDispositions(
    run: PhaseRun,
    passNumber: Int,
    result: ParallelCodeReviewResult,
    reviewRunId: String,
    resolvedTier: CodeReviewExecutionMode,
  ): List<GoalSubtaskBlockerDisposition> {
    if (passNumber < 2) return emptyList()
    val prior = recorder.fetchUnaddressedLedger(run.request.workflowId, run.request.dbPathOverride)
    if (prior.isEmpty()) return emptyList()
    val continuation = run.request.goalContinuation
    val envelope = FeatureTaskRuntimeReviewEnvelope.envelopeMap(
      FeatureTaskRuntimeReviewEnvelope.assemble(
        result = result,
        reviewRunId = reviewRunId,
        cycle = FeatureTaskRuntimeReviewCycleContext(
          passNumber = passNumber,
          resolvedTier = resolvedTier,
          repositoryFingerprint = "disposition-preview",
        ),
      ),
    )
    val verdicts = recorder.recordedFindingVerdicts(envelope, run.request.dbPathOverride)
    val current = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = envelope,
      scope = UnaddressedFindingLedgerScope(
        issueKey = continuation?.parentIssueKey ?: run.request.issueKey,
        subtaskId = continuation?.subtaskId ?: 0,
        workflowId = run.request.workflowId,
        reviewPassNumber = passNumber,
      ),
      recordedVerdicts = verdicts,
    )
    return GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(prior, current, verdicts)
  }

internal fun FeatureTaskRuntimeRunLoop.settleRuntimeOwnedReview(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome {
    val acceptedOutput = runCatching {
      outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
    }.getOrElse { error ->
      return blockAndPersistInPhase(
        run,
        iteration,
        "Runtime-owned review settlement did not validate: ${error.message.orEmpty()}",
        observability,
      )
    }
    val normalizedOutput = acceptedOutput.normalizedOutput
    val outputBytes = outputText.encodeToByteArray()
    recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = run.phaseId,
        attempt = iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = Instant.now(),
        byteSize = outputBytes.size.toLong(),
        sha256 = RejectedOutputDiagnosticService.sha256(outputBytes),
        payload = outputBytes,
        generation = state.evidenceGeneration(run.phaseId),
      ),
      run.request.dbPathOverride,
    )
    if (isGoalReviewRun(run)) {
      persistGoalReviewCompletion(
        run,
        iteration,
        normalizedOutput,
        acceptedOutput.repairEvidence,
        observability,
        fileManifest,
      )?.let { return it }
    } else {
      persistStandaloneReviewCompletion(
        run,
        iteration,
        outputText,
        acceptedOutput,
        observability,
        fileManifest,
      )?.let { return it }
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        acceptedOutput.repairEvidence,
      ),
    )
  }

internal fun FeatureTaskRuntimeRunLoop.runDeclaredValidationGateCycle(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ): PhaseOutcome {
    val validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT
    val changedPaths = validationChangedPaths(run)
    val checkpoint = gitOperations.repositoryFingerprint(run.request.repoRoot).value
      .takeIf(String::isNotBlank)
      ?: return PhaseOutcome.blocked(
        "Validation gate cycle could not resolve a repository checkpoint fingerprint.",
      )
    val iteration = state.nextIteration(run.phaseId)
    val cycle = validationGateCoordinator.execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = run.request.repoRoot,
        request = run.request,
        validationDepth = validationDepth,
        changedPaths = changedPaths,
        repositoryCheckpoint = checkpoint,
        agentTriageLauncher = ValidationGateAgentTriageLauncher { findings ->
          launchValidationGateTriage(
            run = run,
            state = state,
            iteration = iteration,
            observability = observability,
            phaseTokenAccumulator = phaseTokenAccumulator,
            findings = findings,
          )
        },
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, repairIteration, triagePlan ->
          launchValidationGateRepair(
            run = run,
            state = state,
            iteration = iteration,
            observability = observability,
            phaseTokenAccumulator = phaseTokenAccumulator,
            findings = findings,
            repairTurn = repairIteration,
            triagePlan = triagePlan,
          )
        },
      ),
      onGateRunCount = { observability.validationGateProgress() },
    )
    return when (cycle) {
      ValidationGateCycleResult.AbsentFallback ->
        // Pack declares no gate: agent-run validate owns started/completed observability.
        runPhaseAttempts(
          run.copy(agentRunValidateFallback = true),
          state,
          observability,
          phaseTokenAccumulator,
        )
      is ValidationGateCycleResult.Terminal -> {
        observability.started(
          run.phaseId,
          run.resolvedAgent.resolvedAgentId,
          iteration,
          run.modelDirective,
          FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
        )
        when (val terminal = cycle.outcome) {
          is ValidationGateCycleTerminalOutcome.Completed ->
            settleRuntimeOwnedValidation(run, iteration, terminal.output.payload, observability)
          is ValidationGateCycleTerminalOutcome.Blocked ->
            blockAndPersistInPhase(run, iteration, terminal.reason, observability)
        }
      }
    }
  }

