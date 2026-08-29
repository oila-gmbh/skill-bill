@file:Suppress("DestructuringDeclarationWithTooManyEntries")
// FixLoopBranchContext is a deliberate parameter object: the fix-loop branch handlers each read the
// whole set, so destructuring it is what keeps them readable rather than a smell to split.

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

internal data class FeatureTaskRuntimeRunLoopDependencies(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
)

internal data class FeatureTaskRuntimeRunLoopContext(
  val request: FeatureTaskRuntimeRunRequest,
  val state: FeatureTaskRuntimeRunState,
  val observability: FeatureTaskRuntimeRunObservability,
  val specSource: SpecSource,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>,
)

internal data class LaunchRejectionAttribution(
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
)

internal fun resolveLaunchRejectionAttribution(
  declarations: List<PhaseHandoffProjectionDeclaration>,
  projectionName: String,
  currentProducerIteration: (String) -> Int?,
  fallbackProducerIteration: FeatureTaskRuntimeProducerIteration,
): LaunchRejectionAttribution {
  val declaration = declarations.singleOrNull { it.projectionName == projectionName }
    ?: return LaunchRejectionAttribution(
      projectionContractId = "feature_task_runtime.$projectionName",
      producerIteration = fallbackProducerIteration,
    )
  val declaredProducer = declaration.producerIteration
  return LaunchRejectionAttribution(
    projectionContractId = declaration.projectionContractId,
    producerIteration = FeatureTaskRuntimeProducerIteration(
      phaseId = declaredProducer.phaseId,
      iteration = currentProducerIteration(declaredProducer.phaseId) ?: declaredProducer.iteration,
    ),
  )
}

private const val FEATURE_SPEC_ROOT = ".feature-specs"

internal fun isFeatureSpecPathForIssue(path: String, issueKey: String): Boolean {
  val normalized = path.trim().trimEnd('/')
  if (normalized == FEATURE_SPEC_ROOT) return true
  if (!normalized.startsWith("$FEATURE_SPEC_ROOT/")) return false
  val issueDirectory = normalized.removePrefix("$FEATURE_SPEC_ROOT/").substringBefore('/')
  val key = issueKey.trim()
  return issueDirectory == key || issueDirectory.startsWith("$key-")
}

internal fun reconcileCheckpointPathInventory(
  repoRoot: Path,
  issueKey: String,
  specReference: String,
  paths: List<String>,
): List<String> {
  val specPath = Path.of(specReference)
    .let { path -> if (path.isAbsolute) repoRoot.relativize(path) else path }
    .normalize()
    .toString()
  return paths.filterNot { path ->
    path == specPath || isFeatureSpecPathForIssue(path, issueKey)
  }.distinct()
}

// A reservation at or below the completed-review-output count is a stale latch from the pass that
// already produced a result: re-entry must report the next ordinal, not replay pass one forever.
internal fun resolveReviewPassNumber(reservedPassNumber: Int?, completedReviewPassCount: Int): Int {
  reservedPassNumber?.let { pass ->
    require(pass == 1) { "Review reservation allows only pass 1, was $pass." }
  }
  require(completedReviewPassCount <= 1) {
    "Review completed-pass count cannot exceed one, was $completedReviewPassCount."
  }
  return 1
}

@Suppress("LargeClass", "LongMethod", "LongParameterList", "TooManyFunctions")
internal class FeatureTaskRuntimeRunLoop(
  internal val dependencies: FeatureTaskRuntimeRunLoopDependencies,
  context: FeatureTaskRuntimeRunLoopContext,
  internal val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  internal val request = context.request
  internal val state = context.state
  internal val observability = context.observability
  internal val specSource = context.specSource
  internal val transitions = context.transitions
  internal val phaseTokenAccumulator = context.phaseTokenAccumulator
  internal val recorder get() = dependencies.recorder
  internal val goalContinuationRecorder get() = dependencies.goalContinuationRecorder
  internal val outputValidator get() = dependencies.outputValidator
  internal val phaseGates get() = dependencies.phaseGates
  internal val subtaskLauncher get() = dependencies.subtaskLauncher
  internal val phaseSettlementService get() = dependencies.phaseSettlementService
  internal val branchSetupRunner get() = phaseGates.branchSetupRunner
  internal val planningStopper get() = phaseGates.planningStopper
  internal val gitOperations get() = phaseGates.gitOperations
  internal val planningProjectionValidator get() = phaseGates.planningProjectionValidator
  internal val buildReceiptValidator get() = phaseGates.buildReceiptValidator
  internal val validationGateCoordinator get() = phaseGates.validationGateCoordinator
  internal val buildGateCoordinator get() = phaseGates.buildGateCoordinator

  // Content identity of every dirty path the moment a phase stopped writing, keyed by phase. The
  // checkpoint compares against it to tell this run's own work from an edit that landed beside it.
  internal val phaseContentIdentities = mutableMapOf<String, Map<String, String>>()

  internal var resolvedBranch: String? = null

  // Set once a checkpoint has decided ownership in this process. Until then the durable inventory is
  // only a seed and the working tree still bootstraps the scope; afterwards the checkpoint decision is
  // authoritative and ambient dirt must not widen it.
  internal var checkpointOwnershipDecided: Boolean = false
  internal var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  internal var paused: FeatureTaskRuntimeRunReport.Paused? = null
  internal var auditGapRetryResumePending: Boolean = false
  internal var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null
  internal val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = recorder
    .loadOperatorBlockRetry(request.workflowId, request.dbPathOverride)
    ?.takeIf { retry ->
      state.recordFor(retry.phaseId)?.status.let { status -> status == null || status == "pending" }
    }
  internal var operatorBlockRetryCompleted: Boolean = false

  internal var pendingReentry: PendingReentry? = resumedReentry()
  internal var activeReentry: PendingReentry? = pendingReentry

  internal val goalContinuationManifestCommitSha: String? = null

  // SKILL-140: set when a phase launch quarantined an upstream record and requested regeneration, so
  // advance() settles the consumer with the RECORD_REJECTED verdict rather than a normal completion.
  internal var recordRejectionSettlementPending: Boolean = false

  fun drive() {
    if (FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW in state.phasesRequiringDurableGateInvalidation()) {
      val generation = checkNotNull(
        recorder.persistReviewGenerationInvalidation(request.workflowId, request.dbPathOverride),
      ) {
        "Could not durably invalidate legacy review evidence for workflow '${request.workflowId}'."
      }
      state.advanceReviewGeneration(generation)
      state.resetInvalidatedReviewGeneration()
      if (pendingReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        pendingReentry = null
        activeReentry = null
      }
    }
    val auditGapPause = recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)?.let { pause ->
      if (pause.pauseKind != AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD) {
        pause
      } else {
        val migrated = pause.copy(operatorDecision = null, grantConsumed = true)
        recorder.persistAuditGapPause(request.workflowId, migrated, request.dbPathOverride)
        runCatching {
          diagnostics.warning(
            "Cleared a legacy audit-gap warning-threshold pause for workflow '${request.workflowId}'; " +
              "warning thresholds are advisory.",
          )
        }
        migrated
      }
    }
    if (auditGapPause != null) {
      when (auditGapPause.operatorDecision) {
        AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK -> {
          abandonAuditGapSubtask(auditGapPause)
          return
        }
        null -> if (pendingReentry == null) {
          if (!auditGapPause.grantConsumed) {
            reSurfaceAuditGapPause(auditGapPause)
            return
          }
        }
        AUDIT_GAP_PAUSE_DECISION_RETRY_FIX -> {
          if (!auditGapPause.grantConsumed) {
            auditGapRetryResumePending = true
          }
        }
        else -> if (pendingReentry == null) {
          if (!auditGapPause.grantConsumed) {
            reSurfaceAuditGapPause(auditGapPause)
            return
          }
        }
      }
    }
    val resumedReentry = pendingReentry
    if (
      resumedReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
      resumedReentry.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    ) {
      state.auditGapPlanningContextError()?.let { reason ->
        blockInvalidAuditGapRecovery(resumedReentry, reason)
        return
      }
    }
    var phaseId: String? = resumedReentry?.phaseId ?: transitions.forwardPhaseIds.first()
    while (phaseId != null) {
      val settled = advance(phaseId)
      val completedPhaseId = settled.completedPhaseId
      phaseId = if (completedPhaseId != null) {
        nextPhaseAfter(completedPhaseId, requireNotNull(settled.completedVerdict))
      } else {
        null
      }
    }
  }

  private fun advance(phaseId: String): PhaseSettlement {
    phaseEntryBlockReason(phaseId)?.let { reason ->
      blockAt(phaseId, reason)
      return PhaseSettlement.stop()
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(request)) {
      val carriedForward = carriedForwardGoalReviewSettlement()
      if (carriedForward != null) {
        return carriedForward
      }
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT && auditGapRetryResumePending) {
      auditGapRetryResumePending = false
      val carried = settleCarriedForwardAuditGapAudit()
      if (carried != null) return carried
    }
    val reason = if (state.isComplete(phaseId)) {
      state.outputFor(phaseId)
        ?.takeIf { phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN }
        ?.let { applyPlanningStop(phaseId, it) }
    } else {
      establishBranchIfNeeded(phaseId) ?: run {
        runPhaseFor(phaseId)
      }
    }
    return when {
      decomposed != null -> PhaseSettlement.stop()
      recordRejectionSettlementPending -> {
        // The launch quarantined an upstream record: settle this consumer with RECORD_REJECTED so the
        // transition machinery re-enters the producer (or blocks at the regeneration cap). The consumer
        // itself never settles completed — its durable record stays running and it re-runs after the
        // producer regenerates.
        recordRejectionSettlementPending = false
        PhaseSettlement.completed(phaseId, FeatureTaskRuntimeVerdict.RECORD_REJECTED)
      }
      reason != null -> {
        // A phase that paused already owns the report. Recording a block over it would hand a
        // terminal blocked reason to every consumer that reads the blocked report directly, which is
        // exactly the collapse a resumable pause exists to avoid.
        if (paused == null) blockAt(phaseId, reason)
        PhaseSettlement.stop()
      }
      else -> PhaseSettlement.completed(phaseId, state.verdictFor(phaseId))
    }
  }

  // Every reason the phase cannot be entered, evaluated in order and short-circuiting: the declared
  // ordering gate, then the resume cap guard, then the goal review-pass reconciliation.
  fun report(): FeatureTaskRuntimeRunReport {
    val branch = resolvedBranch
      ?: recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch
    return decomposed ?: paused?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = branch,
    )
  }

  internal fun applyOperatorDecision(decision: GoalSubtaskOperatorDecision): String? {
    val auditGapPause = recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)
    if (auditGapPause != null) {
      return applyAuditGapPauseDecision(auditGapPause, decision)
    }
    return "Operator decisions over review remediation are removed; " +
      "the run advances to validate after one implement_fix round."
  }

  /**
   * Applies an operator decision to a durable audit-gap pause without any review state. `retry_fix`
   * sets the single-use grant (in-session flag plus the durable decision); `abandon_subtask` records
   * the decision for the abandon path on resume; `accept_and_advance` is rejected for this pause class.
   */
}
