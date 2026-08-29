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


internal fun FeatureTaskRuntimeRunLoop.declaredCriterionRefs(): List<String> =
    acceptanceCriterionRefsFor(request.runInvariants.acceptanceCriteria.size)

  // Empty by construction: every audit re-decides every declared criterion against the tree, so no
  // criterion is ever durably closed against a later audit. Kept as a seam because the audit briefing
  // and the open-criteria projection both read it.
internal fun FeatureTaskRuntimeRunLoop.durablyClosedCriterionRefs(): List<String> = emptyList()

internal fun FeatureTaskRuntimeRunLoop.openAuditCriterionRefs(closedCriterionRefs: List<String> = durablyClosedCriterionRefs()): List<String> =
    declaredCriterionRefs() - closedCriterionRefs.toSet()

internal fun FeatureTaskRuntimeRunLoop.prepareGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = when {
    run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> GoalReviewRunReady(run)
    isGoalReviewRun(run) -> reserveGoalReviewRun(run, observability)
    else -> prepareStandaloneReviewRun(run, observability)
  }

internal fun FeatureTaskRuntimeRunLoop.prepareStandaloneReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation {
    val resolved = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
      ?: return blockedGoalReviewRun(run, observability, "Standalone review is missing its durable resolved branch.")
    val reviewBaseSha = resolved.reviewBaseSha
      ?: return blockedGoalReviewRun(
        run,
        observability,
        "Standalone review is missing the immutable review base captured before implementation.",
      )
    val result = phaseGates.gitOperations.buildGoalSubtaskReviewInput(
      run.request.repoRoot,
      FeatureTaskRuntimeScopedReviewBaseline.of(
        phaseGates.gitOperations,
        run.request.repoRoot,
        resolved,
        reviewBaseSha,
      ),
      resolved.branch,
    )
    val input = result.input
      ?: return blockedGoalReviewRun(run, observability, result.error.ifBlank { "Standalone review input failed." })
    return GoalReviewRunReady(run.copy(goalReviewInput = input))
  }

  /**
   * Review scope is the checkpoint's owned inventory, not whatever the worktree happens to hold. The
   * persisted inventory is the same one the checkpoint identity digested, so the input a review sees
   * is reproducible from the immutable commit rather than from the tree's current dirt.
   */
internal fun FeatureTaskRuntimeRunLoop.scopedReviewUntrackedExclusions(resolved: FeatureTaskRuntimeResolvedBranch): List<String> =
    FeatureTaskRuntimeScopedReviewBaseline.untrackedExclusions(
      phaseGates.gitOperations,
      request.repoRoot,
      resolved,
    )

internal fun FeatureTaskRuntimeRunLoop.reserveGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    goalContinuationRecorder.reserveGoalReviewPass(run.request.workflowId, run.request.dbPathOverride)
  }.fold(
    onSuccess = { reservation ->
      when (reservation) {
        GoalSubtaskReviewPassReservation.MissingState -> blockedGoalReviewRun(
          run,
          observability,
          "Goal-subtask review state is missing; review_base_sha must be captured before implementation " +
            "and cannot be substituted.",
        )
        is GoalSubtaskReviewPassCarryForward -> GoalReviewRunPreparation.CarryForward
        is GoalSubtaskReviewPassInFlight,
        is GoalSubtaskReviewPassReserved,
        -> buildGoalReviewRun(run, observability)
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        run,
        observability,
        goalReviewPreparationFailure("reservation", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

internal fun FeatureTaskRuntimeRunLoop.buildGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    val resolved = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    goalContinuationRecorder.buildGoalReviewInput(
      workflowId = run.request.workflowId,
      gitOperations = phaseGates.gitOperations,
      repoRoot = run.request.repoRoot,
      scope = FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope(
        dbOverride = run.request.dbPathOverride,
        scopedUntrackedExclusions = resolved?.let(::scopedReviewUntrackedExclusions),
        ownedPathspec = resolved?.workflowOwnedPaths.orEmpty(),
      ),
    )
  }.fold(
    onSuccess = { prepared ->
      when (prepared) {
        GoalSubtaskReviewInputPreparation.MissingState -> {
          blockedGoalReviewRun(run, observability, "Goal-subtask review state disappeared before review launch.")
        }
        is GoalSubtaskReviewInputBlocked -> {
          blockedGoalReviewRun(run, observability, prepared.reason)
        }
        is GoalSubtaskReviewInputReady ->
          GoalReviewRunReady(run.copy(goalReviewInput = prepared.input))
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        run,
        observability,
        goalReviewPreparationFailure("input persistence", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

internal fun FeatureTaskRuntimeRunLoop.goalReviewPreparationFailure(stage: String, error: Throwable): String {
    val location = error.stackTrace.firstOrNull { frame -> frame.className.startsWith("skillbill.") }
      ?.let { frame -> " at ${frame.className}.${frame.methodName}:${frame.lineNumber}" }
      .orEmpty()
    return "Goal-subtask review $stage failed$location: ${error.message.orEmpty()}"
  }

internal fun FeatureTaskRuntimeRunLoop.goalReviewPreparationDisposition(error: Throwable): FeatureTaskRuntimeFailureDisposition =
    if ("[SQLITE_BUSY]" in error.message.orEmpty()) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }

internal fun FeatureTaskRuntimeRunLoop.blockedGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
    reason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ): GoalReviewRunPreparation {
    blockAndPersist(run, 1, reason, observability, failureDisposition = failureDisposition)
    return GoalReviewRunPreparation.Blocked(reason, failureDisposition)
  }

internal fun FeatureTaskRuntimeRunLoop.settleCarriedForwardGoalReview(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = runCatching {
      val output = goalContinuationRecorder.lastGoalReviewResult(run.request.workflowId, run.request.dbPathOverride)
        ?: throw MissingCarriedForwardGoalReviewResultException()
      outputValidator.validatePhaseOutput(output, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
    }.getOrElse { error ->
      val detail = if (error is MissingCarriedForwardGoalReviewResultException) {
        "missing."
      } else {
        "malformed: ${error.message.orEmpty()}"
      }
      return blockAndPersist(
        run,
        state.nextIteration(run.phaseId),
        "Goal-subtask review pass budget is exhausted but its durable raw review result is $detail",
        observability,
      )
    }
    val normalizedOutput = acceptedOutput.normalizedOutput
    val iteration = state.nextIteration(run.phaseId)
    val phaseState = phaseStateRequest(
      run,
      iteration,
      STATUS_COMPLETED,
      finished = true,
      outputArtifact = normalizedOutput.canonicalJson,
      normalizedOutput = normalizedOutput,
      repairEvidence = acceptedOutput.repairEvidence,
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    carriedForwardReviewPersistenceFailure(phaseState, run)?.let { failure ->
      return blockAndPersist(
        run,
        iteration,
        failure,
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        outputArtifact = normalizedOutput.canonicalJson,
        normalizedOutput = normalizedOutput,
        repairEvidence = acceptedOutput.repairEvidence,
      )
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

internal fun FeatureTaskRuntimeRunLoop.carriedForwardReviewPersistenceFailure(
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    run: PhaseRun,
  ): String? {
    val prefix = "Carried-forward goal review could not atomically persist its canonical result."
    return runCatching {
      recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
    }.fold(
      onSuccess = { persisted -> if (persisted) null else prefix },
      onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
    )
  }

internal fun FeatureTaskRuntimeRunLoop.preLaunchBlock(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val persisted = state.persistedBlockedReason(run.phaseId)?.let { persistedReason ->
      val nextIteration = state.nextIteration(run.phaseId)
      val durable = state.recordFor(run.phaseId)
      if (shouldRelaunchPersistedBlock(state, run.phaseId, durable, persistedReason)) {
        return@let null
      }
      val reason = persistedReason.ifBlank {
        "Phase '${run.phaseId}' is durably blocked from a prior run; the runtime re-blocks rather than relaunching."
      }
      PreLaunchBlock(nextIteration, reason, durable)
    }
    val invalidPlanningContext = if (
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    ) {
      state.auditGapPlanningContextError()?.let { reason -> PreLaunchBlock(state.nextIteration(run.phaseId), reason) }
    } else {
      null
    }
    val missing = persisted ?: invalidPlanningContext
      ?: missingRequiredUpstream(run, state)?.let { missingIds ->
        PreLaunchBlock(
          1,
          "Phase '${run.phaseId}' requires upstream output(s) ${missingIds.joinToString()} that are not " +
            "present; the runtime blocks rather than launching the phase blind.",
        )
      }
    return missing?.let { preLaunch ->
      val durable = preLaunch.durableRecord
      blockAndPersist(
        run,
        preLaunch.attemptCount,
        preLaunch.reason,
        observability,
        loopId = durable?.loopId,
        edgeIteration = durable?.edgeIteration,
        failureDisposition = durable?.failureDisposition
          ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        fileManifest = durable?.let {
          FeatureTaskRuntimePhaseFileManifest(it.fileManifestBefore, it.fileManifestAfter)
        },
        outputArtifact = durable?.outputArtifact,
        rejectedOutput = durable?.rejectedOutput,
      )
    }
  }

internal fun FeatureTaskRuntimeRunLoop.missingRequiredUpstream(run: PhaseRun, state: FeatureTaskRuntimeRunState): List<String>? {
    val recoverableAuditRepairSource =
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
        run.reentry.reentryGapCriteria.isNotEmpty()
    return missingUpstream(run.declaration, state.outputs())
      ?.filterNot {
        recoverableAuditRepairSource && it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
      }
      ?.takeIf(List<String>::isNotEmpty)
  }

internal fun FeatureTaskRuntimeRunLoop.isRetryableGoalReviewPreparation(phaseId: String, reason: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return false
    val legacyDatabaseContention =
      reason.startsWith("Goal-subtask review state or durable raw evidence is malformed:") &&
        "[SQLITE_BUSY]" in reason
    return legacyDatabaseContention ||
      "[SQLITE_BUSY]" in reason && (
        reason.startsWith("Goal-subtask review reservation failed") ||
          reason.startsWith("Goal-subtask review input persistence failed")
        )
  }

  // The gate that wrote this reason blocked a goal review on schema-invalid output instead of retrying it,
  // and persisted a terminal needs_user_action disposition. That gate is gone, so such a record is stale
  // rather than terminal: the reserved pass still has no completed output, which the review schema
  // correction loop decides. The remaining attempt budget is deliberately not restarted.
internal fun FeatureTaskRuntimeRunLoop.isRemovedGoalReviewSchemaGateBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass")

  // Continuation used to hard-cap at five segments and persist needs_user_action. That cap is gone, so a
  // durable block naming the old budget is stale rather than terminal: resume must relaunch implement and
  // keep continuing until obligations close.
internal fun FeatureTaskRuntimeRunLoop.isRemovedImplementationContinuationBudgetBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      "exhausted the bounded implementation-continuation budget" in reason

  // A pre-quarantine build blocked a launch-seam planning-projection rejection with a terminal
  // needs_user_action disposition; the current seam instead quarantines the upstream record and
  // regenerates its producer. Such a legacy row is stale, not terminal: re-enter the phase so the live
  // seam routes it through the quarantine-and-regenerate edge. Matches only that one legacy phrase, and
  // only where a regeneration producer exists, so every other launch-seam block and any genuinely
  // unmigratable record keeps its first-occurrence durable block.
internal fun FeatureTaskRuntimeRunLoop.isReenterableLaunchSeamRecordRejection(phaseId: String, reason: String): Boolean =
    reason.contains(LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION) &&
      FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER.containsKey(phaseId)

  // A launch-seam record rejection never ran the consumer, so its attempts are not real fix-loop output
  // attempts. Re-enterable whether the block still carries the launch-seam reason or was already
  // overwritten with the generic fix-loop-exhaustion text on a prior re-entry (recognized from the ledger).
internal fun FeatureTaskRuntimeRunLoop.isReenterableRecordRejection(
    state: FeatureTaskRuntimeRunState,
    phaseId: String,
    reason: String,
  ): Boolean = isReenterableLaunchSeamRecordRejection(phaseId, reason) ||
    state.legacyLaunchSeamRejectionConsumedBudget(phaseId, reason)

  // Decides whether a phase with a persisted block relaunches instead of re-surfacing it, restarting the
  // fix-loop budget for the re-enterable stale-block classes whose prior attempts were not real semantic
  // output failures (goal-review preparation retries, launch-seam record rejections, and the removed
  // implementation-continuation segment cap).
internal fun FeatureTaskRuntimeRunLoop.shouldRelaunchPersistedBlock(
    state: FeatureTaskRuntimeRunState,
    phaseId: String,
    durable: FeatureTaskRuntimePhaseRecord?,
    persistedReason: String,
  ): Boolean {
    val retryReviewPreparation = isRetryableGoalReviewPreparation(phaseId, persistedReason) ||
      state.legacyReviewPreparationRetryConsumedBudget(phaseId, persistedReason)
    val reenterableRecordRejection = isReenterableRecordRejection(state, phaseId, persistedReason)
    val removedContinuationBudget =
      isRemovedImplementationContinuationBudgetBlock(phaseId, persistedReason)
    val restartsBudget = listOf(
      retryReviewPreparation,
      reenterableRecordRejection,
      removedContinuationBudget,
      operatorReopenedPhase(phaseId),
    ).any { it }
    if (restartsBudget) {
      state.restartAttemptBudget(phaseId)
    }
    return shouldRetryPersistedBlock(
      phaseId,
      durable,
      retryReviewPreparation,
      reenterableRecordRejection,
      persistedReason,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.shouldRetryPersistedBlock(
    phaseId: String,
    durable: FeatureTaskRuntimePhaseRecord?,
    retryReviewPreparation: Boolean,
    reenterableRecordRejection: Boolean,
    persistedReason: String,
  ): Boolean {
    val disposition = durable?.failureDisposition
    return when {
      // Ahead of every disposition check: an operator reopen is a decision about this exact block,
      // whatever its class or disposition, so no persisted reason may veto it.
      operatorReopenedPhase(phaseId) -> true
      retryReviewPreparation -> true
      reenterableRecordRejection -> true
      isRemovedGoalReviewSchemaGateBlock(phaseId, persistedReason) -> true
      isRemovedImplementationContinuationBudgetBlock(phaseId, persistedReason) -> true
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        persistedReason.startsWith("Audit-gap recovery requires") -> true
      disposition != null -> disposition.retryOnResume
      else -> FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(phaseId)
    }
  }

internal fun FeatureTaskRuntimeRunLoop.runDeclaredReviewDriverCycle(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome = when (val prepared = prepareRuntimeOwnedReview(run, state)) {
    is RuntimeOwnedReviewBlocked -> prepared.outcome
    is RuntimeOwnedReviewReady -> {
      prepareLaunchForCapture(prepared.run, state, null)
      executePreparedReviewDriver(prepared, observability)
    }
  }

