@file:Suppress("DestructuringDeclarationWithTooManyEntries")
// FixLoopBranchContext is a deliberate parameter object: the fix-loop branch handlers each read the
// whole set, so destructuring it is what keeps them readable rather than a smell to split.

package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
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
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.workflow.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.captureIndexState
import skillbill.ports.workflow.headCommitMessage
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.pathContentIdentities
import skillbill.ports.workflow.repositoryCheckpointFingerprint
import skillbill.ports.workflow.repositoryFingerprint
import skillbill.ports.workflow.repositoryOwnedPaths
import skillbill.ports.workflow.restoreIndexState
import skillbill.ports.workflow.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.runtimePhaseHeadCommit
import skillbill.ports.workflow.stagePaths
import skillbill.ports.workflow.stagedPaths
import skillbill.review.model.ReviewFindingVerdict
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.SpecSource
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewRemediationChurnEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskPauseRelease
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.advanceBlockingFindingIdentities
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.detectReviewRemediationNonProgress
import skillbill.workflow.taskruntime.model.featureTaskRuntimeReviewRemediationChurn
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.upsertRepairReceipt
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

internal data class FeatureTaskRuntimeRunLoopDependencies(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
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
internal fun resolveReviewPassNumber(reservedPassNumber: Int?, completedReviewPassCount: Int): Int =
  reservedPassNumber?.takeIf { it > completedReviewPassCount } ?: (completedReviewPassCount + 1)

@Suppress("LargeClass", "LongMethod", "LongParameterList", "TooManyFunctions")
internal class FeatureTaskRuntimeRunLoop(
  private val dependencies: FeatureTaskRuntimeRunLoopDependencies,
  context: FeatureTaskRuntimeRunLoopContext,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private val request = context.request
  private val state = context.state
  private val observability = context.observability
  private val specSource = context.specSource
  private val transitions = context.transitions
  private val phaseTokenAccumulator = context.phaseTokenAccumulator
  private val recorder get() = dependencies.recorder
  private val goalContinuationRecorder get() = dependencies.goalContinuationRecorder
  private val outputValidator get() = dependencies.outputValidator
  private val phaseGates get() = dependencies.phaseGates
  private val subtaskLauncher get() = dependencies.subtaskLauncher
  private val branchSetupRunner get() = phaseGates.branchSetupRunner
  private val planningStopper get() = phaseGates.planningStopper
  private val gitOperations get() = phaseGates.gitOperations
  private val planningProjectionValidator get() = phaseGates.planningProjectionValidator
  private val validationGateCoordinator get() = phaseGates.validationGateCoordinator

  // Content identity of every dirty path the moment a phase stopped writing, keyed by phase. The
  // checkpoint compares against it to tell this run's own work from an edit that landed beside it.
  private val phaseContentIdentities = mutableMapOf<String, Map<String, String>>()

  private var resolvedBranch: String? = null

  // Set once a checkpoint has decided ownership in this process. Until then the durable inventory is
  // only a seed and the working tree still bootstraps the scope; afterwards the checkpoint decision is
  // authoritative and ambient dirt must not widen it.
  private var checkpointOwnershipDecided: Boolean = false
  private var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  private var paused: FeatureTaskRuntimeRunReport.Paused? = null
  private var operatorGrantedFixIteration: Boolean = false
  private var operatorRetryGrantConsumed: Boolean = false
  private var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null
  private val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = recorder
    .loadOperatorBlockRetry(request.workflowId, request.dbPathOverride)
    ?.takeIf { retry ->
      state.recordFor(retry.phaseId)?.status.let { status -> status == null || status == "pending" }
    }
  private var operatorBlockRetryCompleted: Boolean = false

  private var pendingReentry: PendingReentry? = resumedReentry()
  private var activeReentry: PendingReentry? = pendingReentry

  /**
   * What the previous audit in this process decided: the criteria it named and the tree it read. The
   * non-progress bound compares against these rather than a durable repair ledger, which no longer
   * exists. A fresh process after a crash starts without them and simply skips one comparison; the
   * audit_gap edge cap is the absolute bound either way.
   */
  private var previousAuditCriterionRefs: Set<String> = emptySet()
  private var previousAuditFingerprint: String? = null

  // SKILL-140: set when a phase launch quarantined an upstream record and requested regeneration, so
  // advance() settles the consumer with the RECORD_REJECTED verdict rather than a normal completion.
  private var recordRejectionSettlementPending: Boolean = false

  private fun resumedReentry(): PendingReentry? {
    val (loopId, reentry) = state.latestInFlightReentry() ?: return null
    // A durable re-entry minted under an earlier phase ordering can name a span the live topology
    // cannot legally complete — a review_fix re-entry whose review is now gated behind an audit that
    // never ran. Entering at its destination would step over the gating phase for the rest of the
    // run, so the stale re-entry is dropped and the run restarts from the pipeline head, walking the
    // already-completed phases until it reaches the gating one.
    if (state.spanBlockedByEntryGate(reentry.span)) {
      state.discardStaleReentry(loopId)
      return null
    }
    state.recordEdgeIteration(loopId, reentry.edgeIteration)
    val auditGapLoop = loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    val resumePhaseId = reentry.resumePhaseId
    return PendingReentry(
      phaseId = resumePhaseId,
      loopId = loopId,
      edgeIteration = reentry.edgeIteration,
      drivingVerdict = reentry.drivingVerdict,
      reentryGapCriteria = if (auditGapLoop && resumePhaseId == reentry.destinationPhaseId) {
        auditGapCriteriaForResume()
      } else {
        emptyList()
      },
      expectedRepositoryCheckpoint = if (
        loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      ) {
        reviewedCheckpointFingerprint()
      } else {
        null
      },
    )
  }

  private fun auditGapCriteriaForResume(): List<String> {
    val fromAudit = state.unmetAuditCriteria(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    if (fromAudit.isNotEmpty()) return fromAudit
    return recorder.loadPhaseBriefings(request.workflowId, request.dbPathOverride)
      ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
      ?.unresolvedAuditGapIds
      .orEmpty()
  }

  private fun reviewedCheckpointFingerprint(): String? =
    recorder.loadDeliveredProjections(request.workflowId, request.dbPathOverride)
      ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.repositoryCheckpointFingerprint

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
    val resumedReentry = pendingReentry
    if (
      resumedReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
      resumedReentry.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    ) {
      state.auditGapPlanningContextError()?.let { reason ->
        blockInvalidAuditGapRecovery(resumedReentry, reason)
        return
      }
      if (resumedReentry.reentryGapCriteria.isEmpty()) {
        blockInvalidAuditGapRecovery(
          resumedReentry,
          EMPTY_AUDIT_GAP_CRITERIA_BLOCK_REASON,
        )
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

  @Suppress("CyclomaticComplexMethod")
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
      reason != null && paused == null && bestEffortAdvisoryPhase(phaseId) -> {
        recordAdvisoryPhaseDegradation(phaseId, reason)
        PhaseSettlement.completed(phaseId, FeatureTaskRuntimeVerdict.REPAIR_PLANNED)
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
  private fun phaseEntryBlockReason(phaseId: String): String? = entryGateBlockReason(phaseId)
    ?: capExhaustedOnResume(phaseId)
    ?: reconcileCompletedGoalReviewPass(phaseId)

  // The phase-entry seam of the declared ordering gate. drive() can enter a phase directly from a
  // resumed pending re-entry without ever consulting the transition function, so guarding only the
  // transition would leave a resume hole through which a stale durable record re-enters a gated
  // phase. Both seams evaluate the same declaration-owned predicate.
  //
  // The violation degrades to a durable, resumable Blocked report rather than an escaping throw:
  // an uncaught contract exception here would leave the workflow row running with no blocked reason
  // and skip goal-continuation outcome persistence, so the parent goal could neither resume nor
  // report. Every other governed gate in this runtime blocks the same way.
  private fun entryGateBlockReason(phaseId: String): String? {
    val settledVerdicts = state.settledVerdictsByPhaseId()
    return transitions.entryGateViolation(phaseId, settledVerdicts)?.let { gate ->
      FeatureTaskRuntimePhaseOrderViolationError(
        phaseId = gate.phaseId,
        requiredPhaseId = gate.requiredPhaseId,
        requiredVerdict = gate.requiredVerdict.wireValue,
        observedVerdict = settledVerdicts[gate.requiredPhaseId]?.wireValue,
      ).message
    }
  }

  private fun reconcileCompletedGoalReviewPass(phaseId: String): String? =
    if (isCompletedGoalReview(phaseId)) reconcileReservedGoalReviewPass(phaseId) else null

  private fun isCompletedGoalReview(phaseId: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      isGoalContinuationRun(request) &&
      state.isComplete(phaseId)

  private fun reconcileReservedGoalReviewPass(phaseId: String): String? = runCatching {
    goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
  }.fold(
    onSuccess = { reviewState ->
      when {
        reviewState == null -> "Goal-subtask review state is missing while reconciling a completed review pass."
        reviewState.reservedPassNumber != null -> reconcileReservedGoalReviewOutput(phaseId)
        else -> null
      }
    },
    onFailure = { error ->
      "Goal-subtask review state is malformed while reconciling a completed review pass: ${error.message.orEmpty()}"
    },
  )

  private fun reconcileReservedGoalReviewOutput(phaseId: String): String? = state.outputFor(phaseId)?.payload
    ?.let { output ->
      runCatching {
        outputValidator.validatePhaseOutput(output, sourceLabel = phaseId).requireAcceptedOutput(phaseId)
      }.fold(
        onSuccess = { accepted -> completeReservedGoalReviewPass(output, accepted.normalizedOutput.envelope) },
        onFailure = { error ->
          "Completed goal-subtask review output cannot reconcile its reserved pass: ${error.message.orEmpty()}"
        },
      )
    }
    ?: "Completed goal-subtask review has no durable output to reconcile its reserved pass."

  private fun completeReservedGoalReviewPass(output: String, outputMap: Map<String, Any?>): String? {
    val recordedVerdicts = recorder.recordedFindingVerdicts(outputMap, request.dbPathOverride)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
    return if (
      goalContinuationRecorder.completeGoalReviewPass(
        request = GoalReviewPassCompletionRequest(
          workflowId = request.workflowId,
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = output,
          normalizedOutput = outputMap,
          blockerDispositions = GoalSubtaskReviewSummaryReducer.blockerDispositions(
            outputMap,
            priorBlockerFindingIds(),
          ),
          commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
        ),
        dbOverride = request.dbPathOverride,
      ) == null
    ) {
      "Completed goal-subtask review could not persist its reserved pass."
    } else {
      null
    }
  }

  private fun carriedForwardGoalReviewSettlement(): PhaseSettlement? = runCatching {
    goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
  }.fold(
    onSuccess = { reviewState ->
      reviewState
        ?.takeUnless {
          // A granted retry round makes the recorded pass result stale: replaying it would re-settle
          // the verdict the operator overrode and the committed fix would never be re-reviewed.
          it.retryReviewPending || it.pauseRelease != null
        }
        ?.takeIf { it.reviewCapReached || it.pausedForOperatorDecision || it.reviewSkippedByUser }
        ?.let {
          if (it.pausedForOperatorDecision) {
            val reason = state.recordFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
              ?.blockedReason
              ?: "Goal-subtask review is paused for an operator decision."
            pauseAt(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
              reason,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
            )
            PhaseSettlement.stop()
          } else {
            settleCarriedForwardGoalReview(
              it,
              activeReentry,
            )
          }
        }
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

  private fun settleCarriedForwardGoalReview(
    reviewState: skillbill.workflow.taskruntime.model.GoalSubtaskReviewState,
    reentry: PendingReentry?,
  ): PhaseSettlement =
    runCatching { goalContinuationRecorder.lastGoalReviewResult(request.workflowId, request.dbPathOverride) }.fold(
      onSuccess = { rawResult ->
        rawResult?.let { validateCarriedForwardGoalReview(it, reviewState, reentry) }
          ?: blockCarriedForwardReview("missing")
      },
      onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
    )

  private fun validateCarriedForwardGoalReview(
    rawResult: String,
    reviewState: skillbill.workflow.taskruntime.model.GoalSubtaskReviewState,
    reentry: PendingReentry?,
  ): PhaseSettlement = runCatching {
    val acceptedOutput = outputValidator
      .validatePhaseOutput(rawResult, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      .requireAcceptedOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    recordCarriedForwardGoalReview(
      acceptedOutput.normalizedOutput,
      acceptedOutput.repairEvidence,
      reentry,
    )
  }.fold(
    onSuccess = {
      PhaseSettlement.completed(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        requireNotNull(reviewState.passResults.lastOrNull()).verdict,
      )
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

  private fun recordCarriedForwardGoalReview(
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
    reentry: PendingReentry?,
  ) {
    val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    if (state.isComplete(phaseId)) {
      return
    }
    val iteration = state.nextIteration(phaseId)
    val priorRecord = state.recordFor(phaseId)
    val persisted = recorder.recordCompletedPhase(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_COMPLETED,
        attemptCount = iteration,
        resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
        finished = true,
        outputArtifact = normalizedOutput.canonicalJson,
        normalizedOutput = normalizedOutput,
        repairEvidence = repairEvidence,
        loopId = reentry?.loopId,
        edgeIteration = reentry?.edgeIteration,
      ),
      request.dbPathOverride,
    )
    if (!persisted) {
      error("Carried-forward goal review could not atomically persist its canonical result.")
    }
    if (reentry != null) pendingReentry = null
    state.recordCompleted(
      FeatureTaskRuntimePhaseOutput(
        phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        repairEvidence,
      ),
    )
  }

  private fun blockCarriedForwardReview(detail: String): PhaseSettlement {
    val reason = if (detail == "missing") {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is missing."
    } else {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: $detail"
    }
    blockAt(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, reason)
    return PhaseSettlement.stop()
  }

  @Suppress("CyclomaticComplexMethod", "ReturnCount")
  private fun nextPhaseAfter(phaseId: String, verdict: FeatureTaskRuntimeVerdict): String? {
    operatorPauseRelease(phaseId)?.let { return it.target }
    if (repairEscalationPaused(phaseId, verdict)) return null
    val effectiveVerdict = if (
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      isGoalContinuationRun(request) &&
      goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)?.reviewCapReached == true
    ) {
      FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED
    } else {
      verdict
    }
    val edge = matchingBackwardEdge(phaseId, effectiveVerdict)
    edge?.let(::resumeInFlightReviewFix)?.let { return it }
    val transition = runCatching {
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = transitions,
        currentPhaseId = phaseId,
        verdict = effectiveVerdict,
        edgeIterationCount = edge?.let { effectiveEdgeIterationCount(it) } ?: 0,
        context = FeatureTaskRuntimeTransitionContext(
          settledVerdictsByPhaseId = state.settledVerdictsByPhaseId(),
          unresolvedBlockerPresent = unresolvedBlockerDispositionPresent(),
        ),
      )
    }.getOrElse { error ->
      if (error !is FeatureTaskRuntimePhaseOrderViolationError) throw error
      blockAt(error.phaseId, error.message.orEmpty())
      return null
    }
    return transitionTarget(phaseId, edge, effectiveVerdict, transition)
  }

  private fun transitionTarget(
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge?,
    effectiveVerdict: FeatureTaskRuntimeVerdict,
    transition: FeatureTaskRuntimeNextPhase,
  ): String? = when (transition) {
    is FeatureTaskRuntimeNextPhase.TerminalAdvance -> null
    is FeatureTaskRuntimeNextPhase.TerminalBlock -> {
      blockOnCapExhaustion(phaseId, transition)
      null
    }
    is FeatureTaskRuntimeNextPhase.TerminalPause -> {
      pauseOnUnresolvedBlocker(phaseId, transition)
      null
    }
    is FeatureTaskRuntimeNextPhase.Next -> nextTransitionTarget(phaseId, edge, effectiveVerdict, transition)
  }

  private fun nextTransitionTarget(
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge?,
    effectiveVerdict: FeatureTaskRuntimeVerdict,
    transition: FeatureTaskRuntimeNextPhase.Next,
  ): String? {
    val loopId = transition.loopId
    return when {
      loopId == null && !establishForwardCheckpoint(phaseId, transition.phaseId) -> null
      loopId == null -> transition.phaseId
      loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
        pauseOnReviewRemediationNonConvergence(
          phaseId,
          requireNotNull(edge),
          requireNotNull(transition.edgeIteration),
        ) -> null
      reentersMutatingPhase(requireNotNull(edge), transition.phaseId) &&
        !establishRemediationCheckpoint(phaseId, loopId) -> null
      loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
        !authoritativeAuditRepairPlanMatches(phaseId) -> {
        blockAt(
          phaseId,
          "The accepted audit repair plan was not durably readable and identical before the audit_gap edge.",
        )
        null
      }
      else -> {
        if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
          consumeOperatorRetryGrant()
        }
        recordBackwardEdge(
          edge = edge,
          destinationPhaseId = transition.phaseId,
          loopId = loopId,
          edgeIteration = requireNotNull(transition.edgeIteration),
          verdict = effectiveVerdict,
        )
        transition.phaseId
      }
    }
  }

  // An audit reports unmet criteria and nothing durable is accepted from it, so there is no plan
  // identity for a resumed re-entry to match: the criteria on the audit record are the whole authority.
  private fun authoritativeAuditRepairPlanMatches(auditPhaseId: String): Boolean =
    state.unmetAuditCriteria(auditPhaseId).isNotEmpty()

  private fun reentersMutatingPhase(edge: FeatureTaskRuntimeBackwardEdge, destinationPhaseId: String): Boolean =
    spanBetween(destinationPhaseId, edge.fromPhaseId).any(FeatureTaskRuntimePhaseWorkflowDefinition::isMutatingPhase)

  private fun spanBetween(destinationPhaseId: String, sourcePhaseId: String): List<String> =
    transitions.spanBetween(destinationPhaseId, sourcePhaseId)

  private fun establishForwardCheckpoint(precedingPhaseId: String, destinationPhaseId: String): Boolean = if (
    precedingPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
    destinationPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
  ) {
    checkpointEstablished(
      precedingPhaseId = precedingPhaseId,
      loopId = null,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION,
      blockedReason = ::auditReviewCheckpointBlockedReason,
    )
  } else {
    true
  }

  /**
   * Every path that lets the remediation proceed records the pre-fix sha, including the paths that
   * skip the checkpoint commit. HEAD is the pre-fix tree on all of them, and without the sha the
   * reserved pass silently falls back to labelling the full base-to-current delta as the pre-fix
   * tree — the exact scope bound AC-012 exists to enforce.
   *
   * A Stage commit and its base record are one unit: if `updateReviewState` fails after the commit,
   * HEAD soft-resets to the pre-commit parent so the branch ref and the durable base stay paired.
   */
  @Suppress("ReturnCount")
  private fun establishRemediationCheckpoint(precedingPhaseId: String, loopId: String): Boolean {
    val branch = resolvedBranch
    if (branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
    }
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
    }
    val scope = resolveCheckpointScope(precedingPhaseId, branch, ::remediationCheckpointBlockedReason) ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip ->
        recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        blockAt(precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage -> {
        if (scope.adoptedPaths.isNotEmpty()) {
          runCatching {
            diagnostics.warning(FeatureTaskRuntimeCheckpointScope.adoptionWarning(branch, scope.adoptedPaths))
          }
        }
        val committed = commitRemediationCheckpoint(
          precedingPhaseId = precedingPhaseId,
          branch = branch,
          loopId = loopId,
          ownedPaths = scope.ownedPaths,
        ) ?: return false
        recordRemediationBaseIfNeeded(
          precedingPhaseId = precedingPhaseId,
          loopId = loopId,
          commitSha = committed.commitSha,
          parentSha = committed.parentSha,
        )
      }
    }
  }

  private data class RemediationCheckpointCommit(val commitSha: String, val parentSha: String?)

  @Suppress("ReturnCount")
  private fun commitRemediationCheckpoint(
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    ownedPaths: List<String>,
  ): RemediationCheckpointCommit? {
    val snapshot = phaseGates.gitOperations.captureIndexState(request.repoRoot, ownedPaths)
    if (!snapshot.ok) {
      blockCheckpoint(precedingPhaseId, branch, snapshot.error, ::remediationCheckpointBlockedReason)
      return null
    }
    val parentSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val staged = phaseGates.gitOperations.stagePaths(request.repoRoot, ownedPaths)
    if (!staged.ok) {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(staged.error, ownedPaths, snapshot.value.orEmpty()),
        ::remediationCheckpointBlockedReason,
      )
      return null
    }
    val subtaskIdentity = subtaskCommitIdentity()
    val message = checkpointCommitMessage(
      branch = branch,
      phaseId = precedingPhaseId,
      loopId = loopId,
      identity = subtaskIdentity,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
    )
    val commit = writeSubtaskCommit(branch, message, subtaskIdentity)
    if (!commit.ok) {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(commit.error, ownedPaths, snapshot.value.orEmpty()),
        ::remediationCheckpointBlockedReason,
      )
      return null
    }
    val commitSha = commit.value.orEmpty().trim()
    if (commitSha.isBlank()) {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        "remediation checkpoint commit returned an empty sha",
        ::remediationCheckpointBlockedReason,
      )
      return null
    }
    val recorded = recordCheckpointIdentity(
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = ownedPaths,
      parentSha = parentSha,
      commitSha = commitSha,
      blockedReason = ::remediationCheckpointBlockedReason,
    )
    if (!recorded) {
      rollbackRemediationCheckpointCommit(commitSha, parentSha, identityRecorded = false)
      return null
    }
    return RemediationCheckpointCommit(commitSha = commitSha, parentSha = parentSha)
  }

  private fun recordRemediationBaseIfNeeded(
    precedingPhaseId: String,
    loopId: String,
    commitSha: String?,
    parentSha: String?,
  ): Boolean {
    // Only the review_fix edge reserves a remediation review pass, so only it has a pre-fix base to
    // record. The audit_gap edge re-enters implement without one and must not be gated on it.
    if (loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return true
    val recorded = recordRemediationBaseSha(precedingPhaseId, commitSha)
    if (recorded) return true
    if (commitSha != null) {
      rollbackRemediationCheckpointCommit(commitSha, parentSha, identityRecorded = true)
    }
    return false
  }

  /**
   * Restores the branch tip from the prior checkpoint identity commit when one exists; otherwise
   * removes the subtask commit and leaves the branch at its pre-subtask tip. Idempotent when HEAD
   * no longer names [commitSha].
   */
  private fun rollbackRemediationCheckpointCommit(commitSha: String, parentSha: String?, identityRecorded: Boolean) {
    val normalizedCommit = commitSha.trim()
    val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    if (!head.ok || head.value.trim() != normalizedCommit) return
    val subtaskId = request.goalContinuation?.subtaskId?.toString()
      ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
    val identities = runCatching {
      recorder.loadCheckpointIdentities(request.workflowId, request.dbPathOverride)
    }.fold(
      onSuccess = { loaded -> loaded.orEmpty() },
      onFailure = { error ->
        recordRemediationRollbackDegradation(
          seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
          valueUsed = request.workflowId,
          valueExpected = "checkpoint identities for rollback",
          cause = "loadCheckpointIdentities failed: " +
            error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() },
        )
        emptyList()
      },
    )
      .filter { it.issueKey == request.issueKey && it.subtaskId == subtaskId }
      .sortedBy { it.sequenceNumber }
    val restoreSha = remediationRollbackTargetSha(
      identities = identities,
      commitSha = normalizedCommit,
      parentSha = parentSha,
      identityRecorded = identityRecorded,
    ) ?: return
    val reset = phaseGates.gitOperations.resetSoftToCommit(request.repoRoot, restoreSha)
    if (!reset.ok) {
      recordRemediationRollbackDegradation(
        seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
        valueUsed = restoreSha,
        valueExpected = "successful soft reset to restore target",
        cause = reset.error.ifBlank { "resetSoftToCommit failed" },
      )
    }
  }

  private fun recordRemediationRollbackDegradation(
    seam: String,
    valueUsed: String,
    valueExpected: String,
    cause: String,
  ) {
    goalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
      workflowId = request.workflowId,
      signal = RemediationDegradationSignal(
        seam = seam,
        valueUsed = valueUsed,
        valueExpected = valueExpected,
        cause = cause,
      ),
      dbOverride = request.dbPathOverride,
    )
  }

  private fun remediationRollbackTargetSha(
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
  ): String? {
    val fallback = parentSha?.trim()?.takeIf(String::isNotBlank)
    val predecessor = rollbackPredecessor(identities, commitSha, identityRecorded) ?: return fallback
    return resolvedPredecessorSha(predecessor) ?: fallback
  }

  private fun rollbackPredecessor(
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    identityRecorded: Boolean,
  ): FeatureTaskRuntimeCheckpointIdentity? {
    val currentIdentity = if (identityRecorded) identities.lastOrNull { it.commitSha == commitSha } else null
    return when {
      currentIdentity != null && currentIdentity.sequenceNumber > 0 ->
        identities.find { it.sequenceNumber == currentIdentity.sequenceNumber - 1 }
      currentIdentity != null -> null
      else -> identities.maxByOrNull { it.sequenceNumber }
    }
  }

  private fun resolvedPredecessorSha(predecessor: FeatureTaskRuntimeCheckpointIdentity): String? {
    val predecessorCommitSha = predecessor.commitSha.trim()
    if (predecessorCommitSha.isBlank()) {
      recordRemediationRollbackDegradation(
        seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
        valueUsed = "(blank)",
        valueExpected = "resolvable predecessor identity commit",
        cause = "predecessor identity commit sha was missing or blank",
      )
      return null
    }
    val resolved = phaseGates.gitOperations.resolveCommit(request.repoRoot, predecessorCommitSha)
    val predecessorSha = resolved.value.orEmpty().trim().takeIf { resolved.ok && it.isNotBlank() }
    if (predecessorSha == null) {
      recordRemediationRollbackDegradation(
        seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
        valueUsed = predecessorCommitSha,
        valueExpected = "resolvable predecessor identity commit",
        cause = resolved.error.takeIf { !resolved.ok && it.isNotBlank() }
          ?: "predecessor commit '$predecessorCommitSha' did not resolve",
      )
    }
    return predecessorSha
  }

  /**
   * A checkpoint commits the inventory this workflow owns and nothing else. The trigger is the OWNED
   * delta, not a non-blank `git status`: a tree dirty only with someone else's work has nothing for
   * this workflow to checkpoint, and committing it would attribute their changes to this run.
   */
  private fun checkpointEstablished(
    precedingPhaseId: String,
    loopId: String?,
    intent: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val branch = resolvedBranch
    if (branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return true
    }
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return true
    }
    val scope = resolveCheckpointScope(precedingPhaseId, branch, blockedReason) ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip -> true
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        blockAt(precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage -> {
        if (scope.adoptedPaths.isNotEmpty()) {
          runCatching {
            diagnostics.warning(FeatureTaskRuntimeCheckpointScope.adoptionWarning(branch, scope.adoptedPaths))
          }
        }
        commitCheckpoint(
          precedingPhaseId = precedingPhaseId,
          branch = branch,
          loopId = loopId,
          intent = intent,
          ownedPaths = scope.ownedPaths,
          blockedReason = blockedReason,
        )
      }
    }
  }

  /**
   * Resolves what this checkpoint may stage. Returns null when a git read failed and the phase was
   * already blocked; an unmeasurable inventory can never degrade into "owns nothing", because a
   * checkpoint reading that would skip silently and leave the phase's work uncommitted.
   */
  private fun resolveCheckpointScope(
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
    val worktreeDelta = checkpointWorktreeDelta(resolved?.baselineOwnedPathsForCheckpoint().orEmpty())
      ?: return blockCheckpointScope(
        precedingPhaseId,
        branch,
        "the owned-path inventory could not be read",
        blockedReason,
      )
    val staged = phaseGates.gitOperations.stagedPaths(request.repoRoot)
    if (!staged.ok) {
      return blockCheckpointScope(precedingPhaseId, branch, staged.error, blockedReason)
    }
    val stagedPaths = staged.value.orEmpty().split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
    val persistedOwned = resolved?.workflowOwnedPaths.orEmpty()
    // Governed feature specs a previous checkpoint already recorded as owned: the guard must not
    // re-report them as newly introduced, or the run hard-blocks forever on its own leftover state.
    val evictedFeatureSpecs = persistedOwned
      .filter { path -> isFeatureSpecPathForIssue(path, request.issueKey) }
      .toSet()
    val phaseWritten = phaseWrittenPaths(precedingPhaseId, worktreeDelta, persistedOwned)
      .filterNot { it in evictedFeatureSpecs }
    val ownedInventory = reconcileCheckpointPathInventory(
      repoRoot = request.repoRoot,
      issueKey = request.issueKey,
      specReference = request.runInvariants.specReference,
      // The persisted inventory is the sole ownership authority. It is extended with the paths the
      // writing phases themselves wrote — never with whatever else happens to be dirty, which is how
      // someone else's concurrent edit used to be adopted and committed as this run's work.
      // Governed feature specs never become owned, so the persisted inventory contains implementation
      // paths only. The runtime never stages them; a human operator may already have committed them.
      paths = (
        resolved?.workflowOwnedPaths.orEmpty() +
          phaseWritten.takeIf { mayExtendOwnedInventory(precedingPhaseId) }.orEmpty() +
          writingPhaseIntroducedPaths(worktreeDelta)
        ).distinct()
        .filterNot { path -> isFeatureSpecPathForIssue(path, request.issueKey) }
        .filterNot { FeatureTaskRuntimeCheckpointScope.isForeignGovernedSpecPath(it, request.issueKey) },
    )
    persistOwnedInventory(ownedInventory, resolved?.workflowOwnedPaths.orEmpty())
    checkpointOwnershipDecided = true
    // Nothing has been staged by this checkpoint yet, so every entry in the index arrived from
    // outside it. The scope decision keeps only the ones this run also owns: those are the genuinely
    // ambiguous overlaps. A purely foreign staged path is left alone, which is what lets a
    // concurrently prepared issue coexist without producing a false block.
    return FeatureTaskRuntimeCheckpointScope.decide(
      FeatureTaskRuntimeCheckpointScopeInput(
        issueKey = request.issueKey,
        ownedPaths = ownedInventory,
        phaseIntroducedPaths = phaseWritten,
        worktreeDeltaPaths = worktreeDelta,
        foreignStagedPaths = stagedPaths,
        concurrentlyModifiedOwnedPaths = concurrentlyModifiedOwnedPaths(precedingPhaseId, ownedInventory),
      ),
    )
  }

  /**
   * Only a phase that is allowed to write may bring new paths into the workflow's ownership. A
   * read-only phase that produced a file did something outside its authority, and adopting that file
   * here would turn the boundary AC-003 exists to enforce into a formality.
   */
  private fun mayExtendOwnedInventory(phaseId: String): Boolean = phaseId in INVENTORY_EXTENDING_PHASES

  /**
   * Every checkpoint seam runs from a reader phase (audit before review, review before the fix edge),
   * so the preceding phase can never widen ownership on its own. The paths a writing phase introduced
   * and left dirty would then be excluded from both the checkpoint commit and the pathspec-limited
   * review input: work that is neither committed, blocked, nor reviewed. The durable per-phase
   * manifests of the writing phases carry that attribution, so the inventory grows from those and
   * from nothing else.
   */
  private fun writingPhaseIntroducedPaths(worktreeDelta: List<String>): List<String> {
    val records = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride).orEmpty()
    val writingRecords = INVENTORY_EXTENDING_PHASES.mapNotNull { records[it] }
    if (writingRecords.isEmpty()) {
      if (worktreeDelta.isNotEmpty()) {
        runCatching {
          diagnostics.warning(
            "Feature-task-runtime checkpoint has no durable file manifest for any writing phase; " +
              "the whole working-tree delta is treated as this workflow's own writes.",
          )
        }
      }
      return worktreeDelta
    }
    // A writing phase owns both what it created and what it left dirty when it finished. A path that
    // only appears later, while a reader phase is running, was written by somebody else and stays out.
    val introduced = writingRecords.flatMap { it.fileManifestIntroduced + it.fileManifestAfter }.distinct()
    return FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(worktreeDelta, introduced)
  }

  /**
   * The subset of the working-tree delta the phase itself wrote, taken from its own durable
   * before/after file manifest. Without a manifest the run cannot tell its own writes from anyone
   * else's, so it degrades to the whole delta and records that it did: silently narrowing instead
   * would drop the phase's real work out of the checkpoint.
   */
  private fun phaseWrittenPaths(
    phaseId: String,
    worktreeDelta: List<String>,
    persistedInventory: List<String>,
  ): List<String> {
    val record = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)?.get(phaseId)
    if (record == null) {
      if (worktreeDelta.isNotEmpty()) {
        runCatching {
          diagnostics.warning(
            "Feature-task-runtime checkpoint for phase '$phaseId' has no durable file manifest; " +
              "the whole working-tree delta is treated as the phase's own writes.",
          )
        }
      }
      return worktreeDelta
    }
    // What the phase itself introduced, plus the already-owned paths it left dirty. A path that was
    // dirty before the phase ran and that this workflow does not own belongs to someone else, and
    // is the case where the old since-baseline listing silently adopted a stranger's file.
    val owned = persistedInventory.toSet()
    val ownedStillDirty = record.fileManifestAfter.filter { it in owned }
    val manifest = (record.fileManifestIntroduced + ownedStillDirty).distinct()
    return FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(worktreeDelta, manifest)
  }

  private fun persistOwnedInventory(inventory: List<String>, persisted: List<String>) {
    if (inventory.sorted() == persisted.sorted()) return
    recorder.recordWorkflowOwnedPaths(request.workflowId, inventory, request.dbPathOverride)
  }

  /**
   * Owned paths whose content is no longer what the phase left there. The phase's own writes are
   * captured the moment it finishes, so a difference measured here is by definition somebody else's
   * edit landing while the run was between phases — the unstaged half of the overlap ambiguity.
   *
   * An absent capture (a resumed process, a phase that never launched here) yields no comparison
   * rather than a false accusation; the staged-overlap check still applies.
   */
  private fun concurrentlyModifiedOwnedPaths(phaseId: String, ownedPaths: List<String>): List<String> {
    val captured = phaseContentIdentities[phaseId] ?: return emptyList()
    val current = phaseGates.gitOperations.pathContentIdentities(request.repoRoot, ownedPaths)
    if (!current.ok) return emptyList()
    val now = parseContentIdentities(current.value.orEmpty())
    return captured.filter { (path, identity) -> path in now && now[path] != identity }.keys.sorted()
  }

  private fun blockCheckpointScope(
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    blockCheckpoint(precedingPhaseId, branch, error, blockedReason)
    return null
  }

  private fun checkpointWorktreeDelta(baselineOwnedPaths: List<String>): List<String>? {
    val owned = phaseGates.gitOperations.repositoryOwnedPaths(request.repoRoot)
    if (!owned.ok) return null
    val baseline = baselineOwnedPaths.toSet()
    return owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in baseline }
      .filterNot(FeatureTaskRuntimeCheckpointScope::isRuntimePrivatePath)
      .distinct()
      .sorted()
  }

  // The tracked-and-untracked baseline supersedes the untracked-only one; a run resolved before the
  // wider baseline existed still has the narrower one and must keep using it rather than none.
  private fun FeatureTaskRuntimeResolvedBranch.baselineOwnedPathsForCheckpoint(): List<String> =
    baselineOwnedPaths.ifEmpty { baselineUntrackedPaths }

  /**
   * The checkpoint commit has just captured the pre-fix tree, so [commitSha] (or HEAD when the
   * checkpoint was skipped) IS the pre-fix tree. The reserved remediation pass reviews
   * diff(this sha -> post-fix HEAD), which is what materializes a defect the remediation itself
   * introduces instead of leaving it to be caught incidentally.
   */
  private fun recordRemediationBaseSha(precedingPhaseId: String, commitSha: String? = null): Boolean {
    if (!isGoalContinuationRun(request)) return true
    // Without durable review state there is no reserved remediation pass to bound, so there is no
    // base to record and nothing this gate can protect.
    if (goalReviewStateOrNull() == null) return true
    val baseSha = commitSha?.trim()?.takeIf(String::isNotBlank) ?: run {
      val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      if (!head.ok || head.value.isBlank()) {
        return blockRemediationBaseSha(precedingPhaseId, head.error.ifBlank { "HEAD resolved to an empty sha." })
      }
      head.value.trim()
    }
    return runCatching {
      goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
        state.copy(remediationBaseSha = baseSha)
      }
    }.fold(
      onSuccess = { recorded ->
        if (recorded != null) {
          true
        } else {
          blockRemediationBaseSha(precedingPhaseId, "the review state could not be updated.")
        }
      },
      onFailure = { error -> blockRemediationBaseSha(precedingPhaseId, error.message.orEmpty()) },
    )
  }

  private fun completedImplementFixProducedOutputs(run: PhaseRun, outputMap: Map<String, Any?>): Map<String, Any?>? =
    outputMap
      .takeIf {
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX &&
          it["status"] == STATUS_COMPLETED
      }
      ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]).orEmpty() }

  private fun persistImplementFixRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): String? = runCatching {
    goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.upsertRepairReceipt(receipt)
    }
  }.fold(
    onSuccess = { recorded ->
      if (recorded != null) null else "the review state could not be updated with the repair receipt."
    },
    onFailure = { error ->
      recordRepairReceiptWriteFailure(error)
      "the review state could not be updated with the repair receipt."
    },
  )

  private fun recordRepairReceiptWriteFailure(error: Throwable) {
    diagnostics.warning(
      "Feature-task-runtime could not persist the implement_fix repair receipt for issue " +
        "${request.issueKey}, workflow ${request.workflowId}.",
      error,
    )
  }

  private fun settleAndPersistImplementFixRepairReceipt(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    reject: (String, String) -> AttemptResult,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult? {
    val settlement = implementFixRepairReceiptSettlement(run, outputMap)
    owedFindingsAttempt(settlement, fileManifest)?.let { return it }
    settlement.rejectionDetail?.let { detail -> return reject("repair-receipt", detail) }
    val writeFailure = settlement.writeFailureReason ?: return null
    return AttemptResult.settled(
      blockAndPersistInPhase(
        run,
        iteration,
        writeFailure,
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        fileManifest = fileManifest,
      ),
    )
  }

  private fun owedFindingsAttempt(
    settlement: RepairReceiptSettlement,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult? {
    settlement.unaccountedOmittedRefs?.let { omittedRefs ->
      return AttemptResult.unaccountedItems(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        itemNoun = "review findings",
        unaccountedRefs = omittedRefs,
        retryReason = requireNotNull(settlement.unaccountedRetryReason),
        fileManifest = fileManifest,
      )
    }
    return settlement.unresolvedRefs?.let { refs ->
      AttemptResult.unresolvedFindings(
        unresolvedRefs = refs,
        detail = requireNotNull(settlement.unresolvedDetail),
        retryReason = requireNotNull(settlement.unresolvedRetryReason),
        fileManifest = fileManifest,
      )
    }
  }

  private fun implementFixRepairReceiptSettlement(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): RepairReceiptSettlement {
    val produced = completedImplementFixProducedOutputs(run, outputMap) ?: return RepairReceiptSettlement.None
    val reviewState = goalReviewStateOrNull() ?: return repairReceiptShapeSettlement(produced)
    val anchor = repairReceiptAnchor(reviewState) ?: return repairReceiptShapeSettlement(produced)
    return when (val parsed = featureTaskRuntimeParseRepairReceipt(produced, anchor.baseSha, anchor.roundNumber)) {
      FeatureTaskRuntimeRepairReceiptMissing -> RepairReceiptSettlement.None
      is FeatureTaskRuntimeRepairReceiptRejected -> RepairReceiptSettlement.rejected(parsed.rejectionDetail)
      is FeatureTaskRuntimeRepairReceiptValid -> settledRepairReceipt(parsed.receipt, reviewState)
    }
  }

  /**
   * Order is the policy. Undeclared disturbances are stamped onto the receipt first so a round that
   * rewrote settled constructs cannot burn the output-gate budget on a missing declaration.
   * Coverage runs next and is retryable. The declared dead end is settled only once nothing was
   * omitted, and it persists the receipt first so the operator inherits the producer's own account
   * of what still fails.
   */
  private fun settledRepairReceipt(
    receipt: FeatureTaskRuntimeRepairReceipt,
    reviewState: GoalSubtaskReviewState,
  ): RepairReceiptSettlement {
    val undeclaredRefs = featureTaskRuntimeRepairReceiptRuntimeDeclaredDisturbanceRefs(receipt, reviewState)
    val settledReceipt = featureTaskRuntimeRepairReceiptWithDeclaredDisturbances(receipt, reviewState)
    if (undeclaredRefs.isNotEmpty()) {
      runCatching {
        diagnostics.warning(
          "Feature-task-runtime stamped disturbed_remedies for ${undeclaredRefs.joinToString(", ")} " +
            "on issue ${request.issueKey}, workflow ${request.workflowId}: the producer rewrote " +
            "their closing constructs without declaring them. The ledger still reopens those " +
            "findings for the next review.",
        )
      }
    }
    return unaccountedFindingsSettlement(settledReceipt, reviewState)
      ?: persistImplementFixRepairReceipt(settledReceipt)?.let { reason ->
        RepairReceiptSettlement.writeFailed(reason)
      }
      ?: featureTaskRuntimeUnresolvedFindings(settledReceipt)?.let { unresolved ->
        RepairReceiptSettlement.unresolved(unresolved.refs, unresolved.detail, unresolved.retryReason)
      }
      ?: RepairReceiptSettlement.None
  }

  private fun unaccountedFindingsSettlement(
    receipt: FeatureTaskRuntimeRepairReceipt,
    reviewState: GoalSubtaskReviewState,
  ): RepairReceiptSettlement? {
    val omitted = featureTaskRuntimeRepairReceiptOmittedFindings(receipt, reviewState).ifEmpty { return null }
    return RepairReceiptSettlement.unaccounted(
      omittedRefs = omitted.map(::featureTaskRuntimeCompactFindingRef),
      retryReason = featureTaskRuntimeOmittedFindingsRetryReason(omitted),
    )
  }

  private fun repairReceiptShapeSettlement(produced: Map<String, Any?>): RepairReceiptSettlement =
    featureTaskRuntimeRepairReceiptShapeRejection(produced)
      ?.let { detail -> RepairReceiptSettlement.rejected(detail) }
      ?: RepairReceiptSettlement.None

  private fun repairReceiptAnchor(reviewState: GoalSubtaskReviewState): RepairReceiptAnchor? {
    val baseSha = reviewState.remediationBaseSha
    val roundNumber = featureTaskRuntimeRemediationRoundNumberOrNull(reviewState)
    if (baseSha == null || roundNumber == null) {
      recordRepairReceiptDegradation(
        if (baseSha == null) {
          "no durable remediation base sha was recorded for this round"
        } else {
          "the durable remediation round number is not yet established"
        },
      )
      return null
    }
    return RepairReceiptAnchor(baseSha = baseSha, roundNumber = roundNumber)
  }

  private fun recordRepairReceiptDegradation(reason: String) {
    runCatching {
      diagnostics.warning(
        "Feature-task-runtime did not record the implement_fix repair receipt for issue " +
          "${request.issueKey}, workflow ${request.workflowId}: $reason. The remediation repair " +
          "ledger loses this round.",
      )
    }
  }

  private fun settleCompletedImplementationOutput(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    reject: (String, String) -> AttemptResult,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult? {
    incompleteImplementationReason(run, outputMap)?.let { reason ->
      return AttemptResult.incompleteWork(
        operatorReason = reason,
        continuationReason = reason,
        fileManifest = fileManifest,
        normalizedOutput = normalizedOutput,
      )
    }
    return settleAndPersistImplementFixRepairReceipt(
      run,
      outputMap,
      reject,
      iteration,
      observability,
      fileManifest,
    )
  }

  private fun blockRemediationBaseSha(precedingPhaseId: String, error: String): Boolean {
    blockAt(
      precedingPhaseId,
      "Feature-task-runtime could not record the pre-fix remediation base sha before re-entering " +
        "implement_fix" + (if (error.isBlank()) "." else " ($error).") +
        " Without it the reserved remediation pass would silently review the full base-to-current " +
        "delta instead of the remediation delta.",
    )
    return false
  }

  /**
   * Stages exactly [ownedPaths] and commits them. The pre-checkpoint index is snapshotted first, so a
   * staging or commit failure restores the index to what it was rather than leaving a partial
   * mutation that would silently ride along in the user's next commit. The working tree is never
   * touched on any path through here.
   */
  private fun commitCheckpoint(
    precedingPhaseId: String,
    branch: String,
    loopId: String?,
    intent: String,
    ownedPaths: List<String>,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val snapshot = phaseGates.gitOperations.captureIndexState(request.repoRoot, ownedPaths)
    if (!snapshot.ok) {
      return blockCheckpoint(precedingPhaseId, branch, snapshot.error, blockedReason)
    }
    val parentSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val staged = phaseGates.gitOperations.stagePaths(request.repoRoot, ownedPaths)
    if (!staged.ok) {
      return blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(staged.error, ownedPaths, snapshot.value.orEmpty()),
        blockedReason,
      )
    }
    val subtaskIdentity = subtaskCommitIdentity()
    val message = checkpointCommitMessage(
      branch = branch,
      phaseId = precedingPhaseId,
      loopId = loopId,
      identity = subtaskIdentity,
      intent = intent,
    )
    val commit = writeSubtaskCommit(branch, message, subtaskIdentity)
    if (!commit.ok) {
      return blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(commit.error, ownedPaths, snapshot.value.orEmpty()),
        blockedReason,
      )
    }
    return recordCheckpointIdentity(
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = ownedPaths,
      parentSha = parentSha,
      commitSha = commit.value.orEmpty().trim(),
      blockedReason = blockedReason,
    )
  }

  /**
   * The subtask every checkpoint of this run belongs to. A standalone feature-task run owns no
   * decomposed subtask; the reserved literal keeps one commit and one ref namespace per run anyway.
   */
  private fun subtaskCommitIdentity(): FeatureTaskRuntimeSubtaskCommitIdentity =
    FeatureTaskRuntimeSubtaskCommitIdentity(
      issueKey = request.issueKey,
      subtaskId = request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
    )

  private fun checkpointCommitMessage(
    branch: String,
    phaseId: String,
    loopId: String?,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    intent: String,
  ): String {
    val subtaskName = request.goalContinuation?.subtaskName?.trim()?.takeIf(String::isNotBlank)
    // A standalone run has no manifest to carry a name, so only a goal continuation missing one is a
    // degradation worth a record.
    if (subtaskName == null && request.goalContinuation != null) {
      runCatching {
        diagnostics.warning(
          FeatureTaskRuntimeCheckpointMessage.missingSubtaskNameRecord(identity.issueKey, identity.subtaskId),
        )
      }
    }
    return FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = request.issueKey,
      subtaskName = subtaskName,
      metadata = FeatureTaskRuntimeCheckpointMetadata(
        phaseId = phaseId,
        loopId = loopId,
        generation = checkpointGeneration(loopId),
        branch = branch,
        intent = intent,
      ),
      identity = identity,
    )
  }

  private data class SubtaskCommitLedgerState(val commitSha: String?, val nextSequenceNumber: Int)

  /**
   * The durable subtask-commit pointer, read out of the checkpoint-identity ledger that already
   * records it. Deriving it from the ledger rather than storing a second copy is what makes the
   * pointer and the ledger unable to disagree, and it mints the same sequence number the identity
   * append will. A ledger this runtime cannot read reports no pointer at sequence 0, which is exactly
   * the state the append's quarantine-and-regenerate path leaves behind.
   */
  private fun subtaskCommitLedgerState(identity: FeatureTaskRuntimeSubtaskCommitIdentity): SubtaskCommitLedgerState {
    val read = runCatching { recorder.loadCheckpointIdentities(request.workflowId, request.dbPathOverride) }
    val identities = read.getOrNull()
    val cause = read.exceptionOrNull()
      ?.let { "the checkpoint-identity store could not be read (${it.message ?: it::class.simpleName})" }
      ?: "no workflow row recorded any checkpoint identity for this run".takeIf { identities == null }
    if (cause != null) {
      runCatching { diagnostics.warning(ledgerUnavailableRecord(identity, cause)) }
      return SubtaskCommitLedgerState(commitSha = null, nextSequenceNumber = 0)
    }
    val recorded = requireNotNull(identities)
    return SubtaskCommitLedgerState(
      commitSha = recorded
        .filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
        .maxByOrNull { it.sequenceNumber }
        ?.commitSha,
      nextSequenceNumber = (recorded.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    )
  }

  private fun ledgerUnavailableRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, cause: String): String =
    "seam=FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState value_used='no durable pointer, sequence 0' " +
      "value_expected=the recorded checkpoint-identity ledger for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=$cause"

  /**
   * One subtask, one branch commit: the first checkpoint with staged content creates it and every
   * later checkpoint amends it. Failures return an error result so the caller's existing index-restore
   * reporting handles them exactly as a failed create.
   */
  private fun writeSubtaskCommit(
    branch: String,
    message: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): WorkflowGitOperationResult {
    val ledger = subtaskCommitLedgerState(identity)
    val headSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val decision = FeatureTaskRuntimeSubtaskCommitResolver.decide(
      identity = identity,
      durableCommitSha = ledger.commitSha,
      head = FeatureTaskRuntimeSubtaskCommitHeadState(
        sha = headSha,
        commitMessage = if (ledger.commitSha == null && headSha != null) headCommitMessageOrNull() else null,
        isUnpushed = branchHasUnpushedCommits(branch),
      ),
      sequenceNumber = ledger.nextSequenceNumber,
    )
    return phaseGates.gitOperations.writeSubtaskCommitPreservingHistory(
      repoRoot = request.repoRoot,
      decision = decision,
      identity = identity,
      message = message,
      allowUnchangedIndex = false,
      record = { record -> runCatching { diagnostics.warning(record) } },
    )
  }

  private fun headCommitMessageOrNull(): String? =
    phaseGates.gitOperations.headCommitMessage(request.repoRoot).takeIf { it.ok }?.value

  private fun branchHasUnpushedCommits(branch: String): Boolean {
    val unpushed = phaseGates.gitOperations.localBranchHasUnpushedCommits(request.repoRoot, branch)
    return unpushed.ok && unpushed.value.orEmpty().trim().equals("true", ignoreCase = true)
  }

  /**
   * A failed restore is worse than the failure that triggered it: the index is now in an unknown
   * state and the operator has to know that before they touch the repository. It is reported in the
   * block reason rather than swallowed.
   */
  private fun withIndexRestoreOutcome(error: String, ownedPaths: List<String>, snapshot: String): String {
    val restored = phaseGates.gitOperations.restoreIndexState(request.repoRoot, ownedPaths, snapshot)
    return if (restored.ok) {
      "$error; the pre-checkpoint index was restored and the working tree is unchanged"
    } else {
      "$error; the pre-checkpoint index could NOT be restored (${restored.error}) — inspect " +
        "`git status` before committing anything yourself"
    }
  }

  private fun checkpointGeneration(loopId: String?): Int = loopId?.let { state.edgeIterationCount(it) } ?: 0

  private fun recordCheckpointIdentity(
    precedingPhaseId: String,
    branch: String,
    loopId: String?,
    ownedPaths: List<String>,
    parentSha: String?,
    commitSha: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val recorded = runCatching {
      recorder.appendCheckpointIdentity(
        workflowId = request.workflowId,
        issueKey = request.issueKey,
        // A standalone feature-task run owns no decomposed subtask; the reserved literal keeps the
        // ref name well-formed instead of leaving the segment blank.
        subtaskId = request.goalContinuation?.subtaskId?.toString()
          ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
        branch = branch,
        phaseId = precedingPhaseId,
        loopId = loopId,
        generation = checkpointGeneration(loopId),
        parentSha = parentSha,
        ownedPaths = ownedPaths,
        commitSha = commitSha,
        dbOverride = request.dbPathOverride,
      )
    }
    // The commit already exists; without its identity record the review input has no immutable
    // checkpoint to build from and no later phase can prove what this commit was allowed to own.
    return if (recorded.getOrDefault(false)) {
      true
    } else {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        "checkpoint commit '$commitSha' was created but its durable identity record could not be " +
          "written (${recorded.exceptionOrNull()?.message ?: "the workflow row was absent"}), so the " +
          "commit cannot be attributed to this workflow's authority boundary",
        blockedReason,
      )
    }
  }

  private fun blockCheckpoint(
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    blockAt(precedingPhaseId, blockedReason(branch, error))
    return false
  }

  private fun matchingBackwardEdge(
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? =
    transitions.backwardEdges.firstOrNull { it.fromPhaseId == phaseId && it.triggeringVerdict == verdict }

  /**
   * Record-only resume reconstruction: a durable fix record carries this loop's context at the current
   * watermark but no `LOOP_EDGE` ledger row reconstructed it as in-flight, so the reserved iteration is
   * re-entered instead of a fresh one being allocated (no double-applied mutation). It is one-shot per
   * run — the loop is live-claimed the moment either this path or a live edge fire mints an iteration.
   * Without that bound the unbounded loop would re-satisfy this reconstruction on every re-review and
   * keep replaying the already-reviewed fix instead of earning the next remediation pass.
   */
  private fun resumeInFlightReviewFix(edge: FeatureTaskRuntimeBackwardEdge): String? {
    if (edge.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return null
    if (state.isLoopLiveClaimed(edge.loopId)) return null
    val destinationRecord = state.recordFor(edge.destinationPhaseId)
      ?.takeIf { it.loopId == edge.loopId && it.edgeIteration == state.edgeIterationCount(edge.loopId) }
      ?: return null
    val edgeIteration = requireNotNull(destinationRecord.edgeIteration)
    state.reopenForReentry(edge.fromPhaseId)
    state.recordEdgeIteration(edge.loopId, edgeIteration)
    pendingReentry = PendingReentry(
      phaseId = edge.destinationPhaseId,
      loopId = edge.loopId,
      edgeIteration = edgeIteration,
      drivingVerdict = edge.triggeringVerdict,
      expectedRepositoryCheckpoint = reviewedCheckpointFingerprint(),
    )
    activeReentry = pendingReentry
    return edge.destinationPhaseId
  }

  private fun recordBackwardEdge(
    edge: FeatureTaskRuntimeBackwardEdge,
    destinationPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
  ) {
    val reopenedSpan = spanBetween(destinationPhaseId, edge.fromPhaseId)
    reopenedSpan.forEach(state::reopenForReentry)
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      // Invalidate the quarantined producer's settled completion so its rejected record is no longer
      // selected by the handoff contract; the regenerated higher-iteration output supersedes it. In
      // memory the stale output is dropped from resolution; durably the record returns to running so a
      // resume relaunches the producer rather than re-consuming the rejected record.
      state.invalidateProducerOutput(destinationPhaseId)
      recorder.invalidateQuarantinedProducerRecord(
        request.workflowId,
        destinationPhaseId,
        loopId,
        edgeIteration,
        request.dbPathOverride,
      )
    }
    state.recordEdgeIteration(loopId, edgeIteration)
    val reentryGapCriteria = if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      state.unmetAuditCriteria(edge.fromPhaseId)
    } else {
      emptyList()
    }
    pendingReentry = PendingReentry(
      destinationPhaseId,
      loopId,
      edgeIteration,
      verdict,
      reentryGapCriteria,
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        reviewedCheckpointFingerprint()
      } else {
        null
      },
    )
    activeReentry = pendingReentry
    observability.loopEdge(destinationPhaseId, loopId, edgeIteration, verdict)
    warnOnThresholdCrossing(edge, edgeIteration)
  }

  /**
   * Advisory crossing warning for a semantic remediation loop that just passed its declared warning
   * threshold. It is emitted strictly after the durable re-entry ledger row for this iteration, so a
   * crash before the row reruns this fresh path with no prior warning and a crash after it resumes
   * through the non-emitting reuse path — at most one warning per loop and iteration either way. The
   * exact-equality check keeps later iterations silent, and the guard reads only the edge's own
   * declaration, so `review_fix` and `audit_gap` acknowledge independently with no phase-name
   * branching. Emission failures are swallowed: the transition already happened and an advisory
   * message must not be able to change it.
   */
  private fun warnOnThresholdCrossing(edge: FeatureTaskRuntimeBackwardEdge, edgeIteration: Int) {
    val threshold = edge.warnAfterIterations ?: return
    if (edgeIteration != threshold + 1) return
    runCatching { diagnostics.warning(thresholdCrossingWarning(edge.loopId, threshold, edgeIteration)) }
  }

  private fun thresholdCrossingWarning(loopId: String, threshold: Int, edgeIteration: Int): String =
    "Remediation loop '$loopId' exceeded its warning threshold of $threshold: entering iteration " +
      "$edgeIteration for issue ${request.issueKey}, workflow ${request.workflowId}, subtask " +
      "${request.goalContinuation?.subtaskId ?: request.issueKey}, spec " +
      "${request.runInvariants.specReference}. Remediation will continue."

  private fun capExhaustedOnResume(phaseId: String): String? {
    // An operator reopen releases the per-edge cap for this phase too: the reopened record still
    // carries the loop metadata of the visit that exhausted the cap, so leaving this gate in place
    // would re-block the phase at entry and never reach the relaunch the operator asked for.
    if (operatorReopenedPhase(phaseId)) return null
    val record = state.recordFor(phaseId) ?: return null
    return capExhaustionForRecord(phaseId, record)
  }

  private fun capExhaustionForRecord(phaseId: String, record: FeatureTaskRuntimePhaseRecord): String? {
    val loopId = record.loopId
    val iteration = record.edgeIteration
    if (loopId == null || iteration == null || state.isLoopLiveClaimed(loopId)) {
      return null
    }
    val edge = transitions.backwardEdges.firstOrNull { candidate ->
      candidate.loopId == loopId &&
        (candidate.destinationPhaseId == phaseId || candidate.fromPhaseId == phaseId)
    }
    if (edge?.destinationPhaseId == phaseId) {
      val sourceRecord = state.recordFor(edge.fromPhaseId)
      if (
        sourceRecord?.status == STATUS_BLOCKED && sourceRecord.loopId == loopId &&
        sourceRecord.edgeIteration == iteration
      ) {
        return null
      }
    }
    return edge
      ?.takeIf { candidate -> blocksWhenCapExhausted(candidate, iteration) }
      ?.let { capExhaustionReason(it.loopId, iteration, it.triggeringVerdict) }
  }

  private fun blocksWhenCapExhausted(edge: FeatureTaskRuntimeBackwardEdge, iteration: Int): Boolean =
    edge.capExhaustionBehavior == FeatureTaskRuntimeCapExhaustionBehavior.BLOCK &&
      edge.perEdgeCap?.let { iteration >= it } == true

  private fun runPhaseFor(phaseId: String): String? {
    val briefingReentry = pendingReentry?.takeIf { it.phaseId == phaseId }
    if (briefingReentry != null) pendingReentry = null
    val reentry = briefingReentry ?: activeReentry?.takeIf { active ->
      transitions.backwardEdges
        .firstOrNull { it.loopId == active.loopId }
        ?.let { edge -> phaseId in spanBetween(edge.destinationPhaseId, edge.fromPhaseId) } == true
    }?.copy(phaseId = phaseId, reentryGapCriteria = emptyList())
    val outcome = runPhase(phaseId, request, state, observability, specSource, reentry, phaseTokenAccumulator)
    outcome.regenerationTargetPhaseId?.let {
      // The launch seam quarantined an upstream record and requested regeneration. Do not record this
      // consumer as completed; signal advance() to settle it with the RECORD_REJECTED verdict so the
      // transition machinery re-enters the producer.
      recordRejectionSettlementPending = true
      return null
    }
    outcome.pausedReason?.let { return it }
    return outcome.blockedReason ?: run {
      val completedOutput = requireNotNull(outcome.completedOutput)
      state.recordCompleted(completedOutput)
      if (operatorBlockRetry?.phaseId == phaseId) operatorBlockRetryCompleted = true
      applyPlanningStop(phaseId, completedOutput)
    }
  }

  // Only the edge destination gets a LOOP_EDGE ledger entry carrying `verifier_reentry`, so only the
  // destination may defer its start kind to that entry. Every other phase in the reopened span still
  // owns its own start kind.
  private fun isLoopDestination(reentry: PendingReentry): Boolean =
    transitions.backwardEdges.firstOrNull { it.loopId == reentry.loopId }?.destinationPhaseId == reentry.phaseId

  private fun blockInvalidAuditGapRecovery(reentry: PendingReentry, reason: String) {
    val phaseId = reentry.phaseId
    val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    ).resolvedAgentId
    val attempt = state.nextIteration(phaseId)
    val previous = state.recordFor(phaseId)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = attempt,
        resolvedAgentId = resolvedAgentId,
        finished = false,
        outputArtifact = previous?.outputArtifact,
        rejectedOutput = previous?.rejectedOutput,
        blockedReason = reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        loopId = reentry.loopId,
        edgeIteration = reentry.edgeIteration,
      ),
      request.dbPathOverride,
    )
    observability.blocked(phaseId, resolvedAgentId, attempt, reason)
    blockAt(phaseId, reason)
  }

  private fun applyPlanningStop(phaseId: String, planOutput: FeatureTaskRuntimePhaseOutput): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
      return null
    }
    return when (val decision = resolvePlanningStop(planOutput)) {
      is FeatureTaskRuntimePlanningStopDecision.Proceed -> null
      is FeatureTaskRuntimePlanningStopDecision.Decomposed -> {
        decomposed = decision.report
        null
      }
      is FeatureTaskRuntimePlanningStopDecision.Blocked -> {
        persistPlanningStopBlock(phaseId, decision.reason)
        decision.reason
      }
    }
  }

  private fun resolvePlanningStop(planOutput: FeatureTaskRuntimePhaseOutput): FeatureTaskRuntimePlanningStopDecision =
    planningStopper.resolve(
      request = request,
      completedOutput = planOutput,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = resolvedBranch,
      specSource = specSource,
    )

  private fun persistPlanningStopBlock(phaseId: String, reason: String) {
    val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    ).resolvedAgentId
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = resolvedAgentId,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
      request.dbPathOverride,
    )
    observability.blocked(phaseId, resolvedAgentId, 1, reason)
  }

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

  private fun establishBranchIfNeeded(phaseId: String): String? {
    if (!isFileMutating(phaseId)) {
      return null
    }
    val setup = branchSetupRunner.ensureFeatureBranch(request, observability)
    return setup.blockedReason?.also { reason -> persistBranchSetupBlock(phaseId, reason) } ?: run {
      resolvedBranch = requireNotNull(setup.establishedBranch)
      clearRecoveredBranchSetupBlock(phaseId)
      null
    }
  }

  private fun clearRecoveredBranchSetupBlock(phaseId: String) {
    if (!state.hasBranchSetupBlock(phaseId)) {
      return
    }
    state.clearBranchSetupBlock(phaseId)
  }

  private fun persistBranchSetupBlock(phaseId: String, reason: String) {
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
      request.dbPathOverride,
    )
    observability.branchSetupBlocked(phaseId, BRANCH_SETUP_AGENT_ID, reason)
  }

  private fun bestEffortAdvisoryPhase(phaseId: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX

  private fun recordAdvisoryPhaseDegradation(phaseId: String, reason: String) {
    val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    ).resolvedAgentId
    observability.blocked(phaseId, resolvedAgentId, 1, reason)
    blocked = null
  }

  private fun blockAt(phaseId: String, reason: String) {
    blocked = FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      lastIncompletePhase = phaseId,
      blockedReason = reason,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = resolvedBranch,
    )
  }

  private fun blockOnCapExhaustion(phaseId: String, transition: FeatureTaskRuntimeNextPhase.TerminalBlock) {
    val unresolvedFindings = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      emptyList()
    } else {
      state.unresolvedReviewFindings(phaseId)
    }
    val unmetCriteria = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      state.unmetAuditCriteria(phaseId)
    } else {
      emptyList()
    }
    val reason = capExhaustionReason(
      transition.loopId,
      transition.edgeIteration,
      transition.unresolvedVerdict,
      unresolvedFindings,
      unmetCriteria,
    )
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclaration(phaseId, request.runInvariants.featureSize),
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        request.modelAssignment,
      ),
      compaction = request.compactionSettings.directiveFor(phaseId),
      request = request,
      specSource = specSource,
    )
    blockAndPersist(
      run,
      state.nextIteration(phaseId),
      reason,
      observability,
      loopId = transition.loopId,
      edgeIteration = transition.edgeIteration,
      outputArtifact = state.outputFor(phaseId)?.payload,
    )
    blockAt(phaseId, reason)
  }

  private fun effectiveEdgeIterationCount(edge: FeatureTaskRuntimeBackwardEdge): Int =
    state.edgeIterationCount(edge.loopId)

  /**
   * The grant survives the process that recorded it: a resumed run reads `retry_fix` back off the
   * durable review state. Re-pausing clears `operator_decision`, so a subsequent unresolved pass has
   * no grant left and pauses again.
   */
  private fun operatorRetryGrantActive(): Boolean = FeatureTaskRuntimeOperatorRetryGrant.active(
    consumed = operatorRetryGrantConsumed,
    inSessionGrant = operatorGrantedFixIteration,
    persistedDecision = goalReviewStateOrNull()?.operatorDecision,
  )

  private class PauseReleaseTarget(val target: String?)

  /**
   * Routes a recorded operator decision to the outcome it names. `retry_fix` is handled by the grant
   * seam and falls through to the normal backward-edge transition; `accept_and_advance` releases the
   * subtask forward to `validate` with its unresolved Blockers accepted; `abandon_subtask` ends it.
   * Every decision is consumed durably so the release happens exactly once.
   */
  private fun operatorPauseRelease(phaseId: String): PauseReleaseTarget? {
    if (
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX
    ) {
      return null
    }
    return when (goalReviewStateOrNull()?.pauseRelease) {
      null, GoalSubtaskPauseRelease.RETRY_FIX -> null
      GoalSubtaskPauseRelease.ADVANCE -> {
        consumeOperatorRetryGrant()
        PauseReleaseTarget(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
      }
      GoalSubtaskPauseRelease.ABANDON -> {
        consumeOperatorRetryGrant()
        abandonOnOperatorDecision(phaseId)
        PauseReleaseTarget(null)
      }
    }
  }

  private fun abandonOnOperatorDecision(phaseId: String) {
    val unresolvedCount = goalReviewStateOrNull()?.unresolvedBlockerDispositions?.size ?: 0
    blockAt(
      phaseId,
      "The operator chose abandon_subtask while the subtask was paused with $unresolvedCount " +
        "unresolved Blocker disposition(s). The subtask is abandoned rather than repaired.",
    )
    goalContinuationRecorder.recordGoalContinuationState(
      GoalContinuationStateRecordRequest(
        workflowId = request.workflowId,
        workflowStatus = STATUS_ABANDONED,
      ),
      dbOverride = request.dbPathOverride,
    )
  }

  private fun consumeOperatorRetryGrant() {
    operatorRetryGrantConsumed = true
    operatorGrantedFixIteration = false
    // Durable, not just in-session: a resumed run must not read the same decision back and re-grant.
    if (isGoalContinuationRun(request)) {
      goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
        state.consumeOperatorDecision()
      }
    }
  }

  private fun persistResolvedReviewTier(run: PhaseRun, resolution: ReviewPassResolution) {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW || !isGoalContinuationRun(request)) {
      return
    }
    goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.copy(resolvedTier = resolution.resolvedTier, decidingRule = resolution.decidingRule)
    }
  }

  /**
   * Every remediation pass must key one disposition per Blocker its immediately preceding completed
   * pass emitted — including a Blocker that pass introduced itself — so the ids are minted here from
   * that durable pass result rather than invented by the agent. Empty for pass one, which has no
   * prior pass to dispose.
   */
  private fun priorBlockerFindingIds(): List<String> {
    val priorPass = goalReviewStateOrNull()?.passResults?.lastOrNull() ?: return emptyList()
    return priorPass.findings
      .filter { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY }
      // Prefer the id the prior pass's output actually carried, so the ids the prompt asks the agent
      // to disposition against are the ids it saw. The positional id is only a fallback for records
      // written before the review output's own id was captured.
      .mapIndexed { index, finding -> finding.findingId ?: "pass${priorPass.passNumber}-blocker-${index + 1}" }
  }

  /**
   * An unreadable review record still loud-fails: swallowing it would report no unresolved Blocker and
   * walk the child straight past the pause gate. Deriving carried context from a record that did read
   * is advisory, so a malformed entry degrades to no context and the phase launches anyway. Losing the
   * memory costs the round its history; refusing to launch costs the subtask its review.
   */
  private fun remediationRepairLedger(phaseId: String): FeatureTaskRuntimeRepairLedger? {
    if (phaseId !in REMEDIATION_LEDGER_CONSUMER_PHASE_IDS) return null
    val reviewState = goalReviewStateOrNull() ?: return null
    return advisoryContext("repair ledger") { reviewState.repairLedger }
      ?.takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)
  }

  private fun remediationPriorReviewContext(phaseId: String, passNumber: Int?): FeatureTaskRuntimePriorReviewContext? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    if ((passNumber ?: 1) < 2) return null
    val reviewState = goalReviewStateOrNull() ?: return null
    return advisoryContext("prior review pass context") { reviewState.priorReviewContext }
  }

  private fun <T> advisoryContext(label: String, derive: () -> T?): T? = runCatching(derive).getOrElse { error ->
    if (error is Error) throw error
    runCatching {
      diagnostics.warning(
        "Feature-task-runtime could not derive the $label for issue ${request.issueKey}, workflow " +
          "${request.workflowId}: ${error.message ?: error::class.simpleName}. The phase runs without it.",
      )
    }
    null
  }

  private fun goalReviewStateOrNull(): GoalSubtaskReviewState? = if (!isGoalContinuationRun(request)) {
    null
  } else {
    goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
  }

  /**
   * An operator-granted `retry_fix` suppresses the unresolved-Blocker pause for exactly one
   * transition, so the granted `implement_fix` iteration is actually entered instead of the carried
   * PAUSED disposition re-pausing the subtask on resume.
   */
  private fun unresolvedBlockerDispositionPresent(): Boolean =
    FeatureTaskRuntimeOperatorRetryGrant.pausesOnUnresolvedBlocker(
      grantActive = operatorRetryGrantActive(),
      unresolvedBlockerPresent = goalReviewStateOrNull()?.unresolvedBlockerDispositions?.isNotEmpty() == true ||
        goalReviewStateOrNull()?.passResults?.lastOrNull()?.blocksAdvance == true,
    )

  /**
   * Same advance-blocking finding set across consecutive remediation passes with an unchanged
   * reviewed-delta digest pauses for an operator decision instead of re-entering `implement_fix`.
   * An active retry grant suppresses this for exactly one transition, matching the disposition pause.
   *
   * `reviewedDeltaDigest` is the digest of the tree the previous remediation edge judged under the
   * immutable baseline — not a frozen pass-1 snapshot. When this edge continues (findings moved or
   * the tree changed), advance it to the current immutable digest so the next edge compares
   * consecutive reviews. Leaving it at pass 1 after a tree-changing fix would keep digests unequal
   * forever and fail open into an unbounded remediation loop.
   */
  private fun pauseOnReviewRemediationNonConvergence(
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge,
    edgeIteration: Int,
  ): Boolean {
    if (operatorRetryGrantActive()) return false
    val reviewState = goalReviewStateOrNull()
    if (reviewState == null || reviewState.completedPassCount < 2) return false
    val previous = reviewState.passResults[reviewState.completedPassCount - 2]
    val current = reviewState.passResults.last()
    val previousIdentities = advanceBlockingFindingIdentities(previous.findings)
    val currentIdentities = advanceBlockingFindingIdentities(current.findings)
    val previousDigest = reviewState.reviewedDeltaDigest ?: UNPROVEN_REPOSITORY_FINGERPRINT
    val currentDigest = currentImmutableReviewDeltaDigest(reviewState) ?: UNPROVEN_REPOSITORY_FINGERPRINT
    val decision = detectReviewRemediationNonProgress(
      previous = previousIdentities,
      current = currentIdentities,
      previousRepositoryFingerprintOrDigest = previousDigest,
      currentRepositoryFingerprintOrDigest = currentDigest,
    )
    val churn = remediationChurnEvidence(
      reviewState,
      FeatureTaskRuntimePhaseWorkflowDefinition.REMEDIATION_CHURN_CONSECUTIVE_ROUND_THRESHOLD,
    )
    if (!decision.blocked && churn == null) {
      advanceReviewedDeltaDigestAfterRemediationProgress(reviewState, currentDigest)
      return false
    }
    pauseOnRemediationNonProgress(phaseId, edge.loopId, edgeIteration, reviewState, churn)
    return true
  }

  private fun remediationChurnEvidence(
    reviewState: GoalSubtaskReviewState,
    minimumConsecutiveRounds: Int,
  ): FeatureTaskRuntimeReviewRemediationChurnEvidence? = advisoryContext("remediation churn evidence") {
    featureTaskRuntimeReviewRemediationChurn(
      ledger = reviewState.repairLedger,
      passResults = reviewState.passResults,
      minimumConsecutiveRounds = minimumConsecutiveRounds,
    )
  }

  private fun pauseOnRemediationNonProgress(
    phaseId: String,
    loopId: String,
    edgeIteration: Int,
    reviewState: GoalSubtaskReviewState,
    churn: FeatureTaskRuntimeReviewRemediationChurnEvidence?,
  ) {
    val paused = goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.pauseForNonConvergence()
    } ?: reviewState.pauseForNonConvergence()
    pauseOnAdvanceBlockingFindings(
      phaseId = phaseId,
      loopId = loopId,
      edgeIteration = edgeIteration,
      reviewState = paused,
      nonConvergence = true,
      churn = churn,
    )
  }

  /**
   * Records the immutable-baseline digest of the tree this remediation edge just accepted as
   * progress, so the next non-convergence check compares consecutive review trees.
   */
  private fun advanceReviewedDeltaDigestAfterRemediationProgress(
    reviewState: GoalSubtaskReviewState,
    currentDigest: String,
  ) {
    if (currentDigest == UNPROVEN_REPOSITORY_FINGERPRINT) return
    if (currentDigest == reviewState.reviewedDeltaDigest) return
    goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.copy(reviewedDeltaDigest = currentDigest)
    }
  }

  private fun currentImmutableReviewDeltaDigest(reviewState: GoalSubtaskReviewState): String? {
    val goalBranch = request.goalContinuation?.goalBranch ?: return null
    val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
    val baseline = resolved
      ?.let {
        FeatureTaskRuntimeScopedReviewBaseline.of(
          phaseGates.gitOperations,
          request.repoRoot,
          it,
          reviewState.reviewBaseSha,
        )
      }
      ?: GoalSubtaskReviewBaseline(reviewState.reviewBaseSha, reviewState.baselineUntrackedPaths)
    return phaseGates.gitOperations.buildGoalSubtaskReviewInput(
      request.repoRoot,
      baseline,
      goalBranch,
    ).input?.deltaDigest
  }

  /**
   * SKILL-141's non-terminal resumable status, not a block: the persisted review state, its
   * `review_base_sha`, the baseline untracked inventory, and the consumed pass count survive intact
   * so resume never re-reserves a consumed pass. The bounded operator decision over `retry_fix`,
   * `accept_and_advance`, and `abandon_subtask` is what releases it.
   */
  private fun repairEscalationPaused(phaseId: String, verdict: FeatureTaskRuntimeVerdict): Boolean {
    if (
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX ||
      verdict != FeatureTaskRuntimeVerdict.ESCALATED
    ) {
      return false
    }
    if (!designSymptomAlreadyAttempted(phaseId)) return false
    pauseOnRepairEscalation(phaseId)
    return true
  }

  private fun designSymptomAlreadyAttempted(planFixPhaseId: String): Boolean {
    val escalatedRefs = state.repairPlan(planFixPhaseId)?.designSymptomRefs.orEmpty()
    if (escalatedRefs.isEmpty()) return false
    val reviewState = goalReviewStateOrNull() ?: return false
    return advisoryContext("repair ledger") { reviewState.repairLedger }
      ?.hasReopenedEntryFor(escalatedRefs) == true
  }

  private fun pauseOnRepairEscalation(phaseId: String) {
    val reviewState = goalReviewStateOrNull()?.let { state ->
      if (state.pausedForOperatorDecision) {
        state
      } else {
        goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) {
          it.pauseForNonConvergence()
        } ?: state.pauseForNonConvergence()
      }
    }
    pauseOnAdvanceBlockingFindings(
      phaseId = phaseId,
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
      edgeIteration = state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID)
        .coerceAtLeast(1),
      reviewState = reviewState,
      nonConvergence = true,
      churn = reviewState?.let {
        remediationChurnEvidence(
          it,
          FeatureTaskRuntimePhaseWorkflowDefinition.REMEDIATION_ESCALATION_EVIDENCE_MIN_CONSECUTIVE_ROUNDS,
        )
      },
    )
  }

  private fun pauseOnUnresolvedBlocker(phaseId: String, transition: FeatureTaskRuntimeNextPhase.TerminalPause) {
    val reviewState = goalReviewStateOrNull()?.let { state ->
      if (state.pausedForOperatorDecision) {
        state
      } else {
        goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) {
          it.pauseForNonConvergence()
        } ?: state.pauseForNonConvergence()
      }
    }
    pauseOnAdvanceBlockingFindings(
      phaseId = phaseId,
      loopId = transition.loopId,
      edgeIteration = transition.edgeIteration,
      reviewState = reviewState,
      nonConvergence = false,
    )
  }

  /**
   * Goal-facing pause reasons carry severity, count, and sanitized labels only — never paths, line
   * numbers, diff hunks, or raw child-review output. Location evidence stays in the durable artifact
   * for `skill-bill goal findings --issue-key`.
   */
  private fun pauseOnAdvanceBlockingFindings(
    phaseId: String,
    loopId: String,
    edgeIteration: Int,
    reviewState: GoalSubtaskReviewState?,
    nonConvergence: Boolean,
    churn: FeatureTaskRuntimeReviewRemediationChurnEvidence? = null,
  ) {
    val reason = goalFacingPauseReason(reviewState, edgeIteration, nonConvergence, churn)
    goalContinuationRecorder.recordGoalContinuationState(
      GoalContinuationStateRecordRequest(
        workflowId = request.workflowId,
        workflowStatus = STATUS_PAUSED,
      ),
      dbOverride = request.dbPathOverride,
    )
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    // STATUS_PAUSED, not STATUS_BLOCKED: workflowStatusFor maps a blocked phase request back to a
    // blocked workflow row, which would overwrite the paused row written immediately above and turn a
    // resumable pause into a terminal block.
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_PAUSED,
        attemptCount = state.nextIteration(phaseId),
        resolvedAgentId = resolvedAgent.resolvedAgentId,
        finished = false,
        blockedReason = reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        loopId = loopId,
        edgeIteration = edgeIteration,
      ),
      dbOverride = request.dbPathOverride,
    )
    pauseAt(phaseId, reason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX)
  }

  private fun goalFacingPauseReason(
    reviewState: GoalSubtaskReviewState?,
    edgeIteration: Int,
    nonConvergence: Boolean,
    churn: FeatureTaskRuntimeReviewRemediationChurnEvidence?,
  ): String {
    val blocking = reviewState?.passResults?.lastOrNull()?.findings
      ?.filter { it.blocksAdvance }
      .orEmpty()
    val blockerCount = blocking.count { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY }
    val majorCount = blocking.count { it.severity == "major" }
    val dispositionCount = reviewState?.unresolvedBlockerDispositions?.size ?: 0
    val total = when {
      blocking.isNotEmpty() -> blocking.size
      dispositionCount > 0 -> dispositionCount
      else -> 0
    }
    val labels = blocking.map { it.label }.distinct().take(MAX_PAUSE_REASON_LABELS)
    val labelSuffix = if (labels.isEmpty()) {
      ""
    } else {
      ": " + labels.joinToString(", ")
    }
    val trigger = if (nonConvergence) {
      "unchanged across consecutive remediation passes with no repository change"
    } else {
      "still unresolved after remediation"
    }
    val churnClause = churn?.let { " " + it.pauseReasonClause() }.orEmpty()
    return "Goal-subtask review pass $edgeIteration paused with $total advance-blocking " +
      "finding(s) ($blockerCount Blocker, $majorCount Major) $trigger$labelSuffix.$churnClause " +
      "The subtask is paused and resumable; choose retry_fix, accept_and_advance, or abandon_subtask " +
      "to continue. Location-bearing evidence: skill-bill goal findings --issue-key <KEY>."
  }

  private fun pauseAt(phaseId: String, reason: String, resumableStep: String) {
    paused = FeatureTaskRuntimeRunReport.Paused(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      pausedPhase = phaseId,
      pauseReason = reason,
      resumableStep = resumableStep,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = resolvedBranch,
    )
  }

  /**
   * The operator-decision entry point. `retry_fix` grants one fresh `implement_fix` iteration that is
   * exempt from the `review_fix` per-edge cap accounting — the operator choice is the bound, not the
   * cap — while `accept_and_advance` releases the subtask forward to `validate` and `abandon_subtask`
   * takes the existing abandon path.
   */
  internal fun applyOperatorDecision(decision: GoalSubtaskOperatorDecision): String? {
    val reviewState = goalReviewStateOrNull()
      ?: return "No goal-subtask review state is present to apply an operator decision to."
    if (!reviewState.acceptsOperatorDecision) {
      return "The subtask carries no unresolved Blocker or Major; an operator decision is only accepted while it does."
    }
    goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
      state.applyOperatorDecision(decision)
    } ?: return "The operator decision could not be persisted onto the durable review state."
    when (decision) {
      GoalSubtaskOperatorDecision.RETRY_FIX -> operatorGrantedFixIteration = true
      GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE -> operatorGrantedFixIteration = false
      GoalSubtaskOperatorDecision.ABANDON_SUBTASK -> operatorGrantedFixIteration = false
    }
    return null
  }

  private fun remediationCheckpointBlockedReason(branch: String, error: String): String =
    "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
      "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
      " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."

  private fun auditReviewCheckpointBlockedReason(branch: String, error: String): String =
    "Feature-task-runtime could not commit the audited implementation on the feature branch '$branch' " +
      "before review" + (if (error.isBlank()) "." else " ($error).") +
      " Refusing to review an uncommitted final audit iteration."

  private fun capExhaustionReason(
    loopId: String,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
    unresolvedFindings: List<FeatureTaskRuntimeReviewFinding> = emptyList(),
    unmetCriteria: List<String> = emptyList(),
  ): String {
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      return regenerationCapExhaustionReason(loopId, edgeIteration)
    }
    val findingsSuffix = if (unresolvedFindings.isEmpty()) {
      ""
    } else {
      " Unresolved findings: " +
        unresolvedFindings.joinToString("; ") { "[${it.severity.wireValue}] ${it.message}" } + "."
    }
    val criteriaSuffix = if (unmetCriteria.isEmpty()) {
      ""
    } else {
      " Unmet criteria: " + unmetCriteria.joinToString("; ") + "."
    }
    return "Backward-edge loop '$loopId' exhausted its per-edge cap after $edgeIteration iteration(s) with the " +
      "verdict '${verdict.wireValue}' still unresolved; the run blocks rather than re-entering past the cap." +
      findingsSuffix + criteriaSuffix
  }

  // SKILL-140: AC-004 cap-exhaustion reason for a regeneration loop, naming the quarantined record, the
  // producing phase, and the attempt count. Shared by the in-run transition block and the pre-launch
  // resume cap guard, so both surface the same actionable message.
  private fun regenerationCapExhaustionReason(loopId: String, edgeIteration: Int): String {
    val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.entries
      .firstOrNull { it.value == loopId }?.key
    val latest = producer?.let { producing ->
      recorder.loadQuarantinedRecords(request.workflowId, request.dbPathOverride)
        .orEmpty()
        .lastOrNull { it.producingPhaseId == producing }
    }
    val recordId = latest?.recordIdentifier() ?: producer?.let { "$it#<unknown-iteration>" } ?: "<unknown>"
    return "Quarantine-and-regenerate loop '$loopId' exhausted its regeneration cap after $edgeIteration " +
      "attempt(s): the quarantined record '$recordId' produced by phase '${producer ?: "<unknown>"}' still " +
      "fails projection validation. The run blocks durably rather than regenerating past the cap; recover the " +
      "record out of band by deleting or migrating the offending row."
  }

  private sealed interface PhaseSettlement {
    private data object Stopped : PhaseSettlement
    private data class Completed(val phaseId: String, val verdict: FeatureTaskRuntimeVerdict) : PhaseSettlement

    val completedPhaseId: String? get() = (this as? Completed)?.phaseId
    val completedVerdict: FeatureTaskRuntimeVerdict? get() = (this as? Completed)?.verdict

    companion object {
      fun stop(): PhaseSettlement = Stopped
      fun completed(phaseId: String, verdict: FeatureTaskRuntimeVerdict): PhaseSettlement = Completed(phaseId, verdict)
    }
  }

  private data class PendingReentry(
    val phaseId: String,
    val loopId: String,
    val edgeIteration: Int,
    val drivingVerdict: FeatureTaskRuntimeVerdict,
    val reentryGapCriteria: List<String> = emptyList(),
    val expectedRepositoryCheckpoint: String? = null,
  )

  @Suppress("LongParameterList")
  private fun runPhase(
    phaseId: String,
    request: FeatureTaskRuntimeRunRequest,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    specSource: SpecSource,
    reentry: PendingReentry?,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    )
    val declaration = phaseDeclaration(phaseId, request.runInvariants.featureSize).let { declaration ->
      if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
      ) {
        declaration.copy(
          projectionDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.auditRemediationProjections(),
        )
      } else if (
        phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
        reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      ) {
        declaration.copy(
          projectionDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.reviewRetryProjections(),
        )
      } else {
        declaration
      }
    }
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = declaration,
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        request.modelAssignment,
      ),
      compaction = request.compactionSettings.directiveFor(phaseId),
      request = request,
      specSource = specSource,
      reentry = reentry,
    )
    preLaunchBlock(run, state, observability)?.let { return it }
    return when (val prepared = prepareGoalReviewRun(run, observability)) {
      is GoalReviewRunReady -> when {
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
          runDeclaredReviewDriverCycle(prepared.run, state, observability)
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
          runDeclaredValidationGateCycle(prepared.run, state, observability, phaseTokenAccumulator)
        else -> runPhaseAttempts(prepared.run, state, observability, phaseTokenAccumulator)
      }
      GoalReviewRunPreparation.CarryForward -> settleCarriedForwardGoalReview(
        run = run,
        state = state,
        observability = observability,
      )
      is GoalReviewRunPreparation.Blocked -> PhaseOutcome.blocked(prepared.reason)
    }
  }

  private fun declaredCriterionRefs(): List<String> =
    acceptanceCriterionRefsFor(request.runInvariants.acceptanceCriteria.size)

  // Empty by construction: every audit re-decides every declared criterion against the tree, so no
  // criterion is ever durably closed against a later audit. Kept as a seam because the audit briefing
  // and the open-criteria projection both read it.
  private fun durablyClosedCriterionRefs(): List<String> = emptyList()

  private fun openAuditCriterionRefs(closedCriterionRefs: List<String> = durablyClosedCriterionRefs()): List<String> =
    declaredCriterionRefs() - closedCriterionRefs.toSet()

  private fun prepareGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = when {
    run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> GoalReviewRunReady(run)
    isGoalReviewRun(run) -> reserveGoalReviewRun(run, observability)
    else -> prepareStandaloneReviewRun(run, observability)
  }

  private fun prepareStandaloneReviewRun(
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
  private fun scopedReviewUntrackedExclusions(resolved: FeatureTaskRuntimeResolvedBranch): List<String> =
    FeatureTaskRuntimeScopedReviewBaseline.untrackedExclusions(
      phaseGates.gitOperations,
      request.repoRoot,
      resolved,
    )

  private fun reserveGoalReviewRun(
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

  private fun buildGoalReviewRun(
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

  private fun goalReviewPreparationFailure(stage: String, error: Throwable): String {
    val location = error.stackTrace.firstOrNull { frame -> frame.className.startsWith("skillbill.") }
      ?.let { frame -> " at ${frame.className}.${frame.methodName}:${frame.lineNumber}" }
      .orEmpty()
    return "Goal-subtask review $stage failed$location: ${error.message.orEmpty()}"
  }

  private fun goalReviewPreparationDisposition(error: Throwable): FeatureTaskRuntimeFailureDisposition =
    if ("[SQLITE_BUSY]" in error.message.orEmpty()) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }

  private fun blockedGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
    reason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ): GoalReviewRunPreparation {
    blockAndPersist(run, 1, reason, observability, failureDisposition = failureDisposition)
    return GoalReviewRunPreparation.Blocked(reason, failureDisposition)
  }

  private class MissingCarriedForwardGoalReviewResultException : IllegalStateException()

  private fun settleCarriedForwardGoalReview(
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

  private fun carriedForwardReviewPersistenceFailure(
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

  private fun preLaunchBlock(
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

  private fun missingRequiredUpstream(run: PhaseRun, state: FeatureTaskRuntimeRunState): List<String>? {
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

  private fun isRetryableGoalReviewPreparation(phaseId: String, reason: String): Boolean {
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
  private fun isRemovedGoalReviewSchemaGateBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass")

  // Continuation used to hard-cap at five segments and persist needs_user_action. That cap is gone, so a
  // durable block naming the old budget is stale rather than terminal: resume must relaunch implement and
  // keep continuing until obligations close.
  private fun isRemovedImplementationContinuationBudgetBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      "exhausted the bounded implementation-continuation budget" in reason

  private fun isStaleEmptyAuditGapCriteriaBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      reason == EMPTY_AUDIT_GAP_CRITERIA_BLOCK_REASON

  // A pre-quarantine build blocked a launch-seam planning-projection rejection with a terminal
  // needs_user_action disposition; the current seam instead quarantines the upstream record and
  // regenerates its producer. Such a legacy row is stale, not terminal: re-enter the phase so the live
  // seam routes it through the quarantine-and-regenerate edge. Matches only that one legacy phrase, and
  // only where a regeneration producer exists, so every other launch-seam block and any genuinely
  // unmigratable record keeps its first-occurrence durable block.
  private fun isReenterableLaunchSeamRecordRejection(phaseId: String, reason: String): Boolean =
    reason.contains(LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION) &&
      FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER.containsKey(phaseId)

  // A launch-seam record rejection never ran the consumer, so its attempts are not real fix-loop output
  // attempts. Re-enterable whether the block still carries the launch-seam reason or was already
  // overwritten with the generic fix-loop-exhaustion text on a prior re-entry (recognized from the ledger).
  private fun isReenterableRecordRejection(
    state: FeatureTaskRuntimeRunState,
    phaseId: String,
    reason: String,
  ): Boolean = isReenterableLaunchSeamRecordRejection(phaseId, reason) ||
    state.legacyLaunchSeamRejectionConsumedBudget(phaseId, reason)

  // Decides whether a phase with a persisted block relaunches instead of re-surfacing it, restarting the
  // fix-loop budget for the re-enterable stale-block classes whose prior attempts were not real semantic
  // output failures (goal-review preparation retries, launch-seam record rejections, and the removed
  // implementation-continuation segment cap).
  private fun shouldRelaunchPersistedBlock(
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
    val staleEmptyAuditGapCriteria = isStaleEmptyAuditGapCriteriaBlock(phaseId, persistedReason)
    val restartsBudget = listOf(
      retryReviewPreparation,
      reenterableRecordRejection,
      removedContinuationBudget,
      staleEmptyAuditGapCriteria,
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

  private fun shouldRetryPersistedBlock(
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
      isStaleEmptyAuditGapCriteriaBlock(phaseId, persistedReason) -> true
      disposition != null -> disposition.retryOnResume
      else -> FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(phaseId)
    }
  }

  private fun runDeclaredReviewDriverCycle(
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

  private sealed class RuntimeOwnedReviewPrep
  private data class RuntimeOwnedReviewReady(
    val run: PhaseRun,
    val launch: RuntimeOwnedReviewLaunch,
    val driverRequest: ParallelCodeReviewRequest,
  ) : RuntimeOwnedReviewPrep()
  private data class RuntimeOwnedReviewBlocked(val outcome: PhaseOutcome) : RuntimeOwnedReviewPrep()

  private data class RuntimeOwnedReviewLaunch(
    val iteration: Int,
    val passNumber: Int,
    val resolvedTier: skillbill.workflow.model.CodeReviewExecutionMode,
    val reviewRunId: String,
    val checkpoint: String,
  )

  private fun prepareRuntimeOwnedReview(run: PhaseRun, state: FeatureTaskRuntimeRunState): RuntimeOwnedReviewPrep {
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
        resolvedTier = resolution.resolvedTier,
        reviewRunId = reviewRunId,
        checkpoint = checkpoint,
      ),
      driverRequest = FeatureTaskRuntimeReviewDriverMapper.request(
        input = input,
        runInvariants = run.request.runInvariants,
        agents = FeatureTaskRuntimeReviewDriverAgents(
          agent1Id = run.resolvedAgent.resolvedAgentId,
          parallelReviewAgent = run.request.parallelReviewAgent,
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

  private fun executePreparedReviewDriver(
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

  private sealed class ReviewDriverAttempt
  private data class ReviewDriverReady(val result: ParallelCodeReviewResult) : ReviewDriverAttempt()
  private data class ReviewDriverFailed(
    val reason: String,
    val disposition: FeatureTaskRuntimeFailureDisposition =
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ) : ReviewDriverAttempt()

  private fun invokeReviewDriver(request: ParallelCodeReviewRequest): ReviewDriverAttempt = try {
    ReviewDriverReady(phaseGates.reviewDriver.run(request))
  } catch (error: skillbill.application.model.DiffResolutionException) {
    ReviewDriverFailed(
      "Runtime-owned review could not resolve the child-owned diff: ${error.message.orEmpty()}",
    )
  } catch (error: skillbill.application.model.UsageValidationException) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  } catch (error: skillbill.application.model.StackDetectionException) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  } catch (error: skillbill.review.context.model.ReviewContextBudgetExceededException) {
    ReviewDriverFailed(
      "Runtime-owned review exceeded a review-context budget: ${error.message.orEmpty()}",
    )
  } catch (error: skillbill.error.UnreadableSpecIntentProjectionError) {
    ReviewDriverFailed(
      "Runtime-owned review could not read the spec intent projection: ${error.message.orEmpty()}",
    )
  } catch (error: skillbill.error.InvalidReviewContextSchemaError) {
    ReviewDriverFailed(
      "Runtime-owned review produced an invalid review-context envelope: ${error.message.orEmpty()}",
    )
  } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error::class.simpleName}: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  }

  private fun settleReviewDriverResult(
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

  private fun reviewBaselineUntrackedPaths(run: PhaseRun): List<String> =
    recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
      ?.baselineUntrackedPaths
      ?.takeIf { it.isNotEmpty() }
      ?: goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
        ?.baselineUntrackedPaths
        .orEmpty()

  private fun failedReviewLaneReason(result: ParallelCodeReviewResult): String? {
    val failed = listOf(result.lane1, result.lane2).filter { lane ->
      lane.agentId.isNotBlank() && !lane.success
    }
    if (failed.isEmpty()) return null
    val detail = failed.first().failureReason?.takeIf(String::isNotBlank) ?: "lane failed"
    return "Feature-task-runtime phase 'review' $detail"
  }

  private fun reviewBlockerDispositions(
    run: PhaseRun,
    passNumber: Int,
    result: ParallelCodeReviewResult,
    reviewRunId: String,
    resolvedTier: skillbill.workflow.model.CodeReviewExecutionMode,
  ): List<skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition> {
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

  private fun settleRuntimeOwnedReview(
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
        recordedAt = java.time.Instant.now(),
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
      val persisted = recorder.recordCompletedPhase(
        phaseStateRequest(
          run,
          iteration,
          STATUS_COMPLETED,
          finished = true,
          outputArtifact = outputText,
          fileManifest = fileManifest,
          normalizedOutput = normalizedOutput,
          repairEvidence = acceptedOutput.repairEvidence,
          reviewRunId = state.recordFor(run.phaseId)?.reviewRunId,
        ),
        run.request.dbPathOverride,
      )
      if (!persisted) {
        return blockAndPersistInPhase(
          run,
          iteration,
          "Runtime-owned review settlement could not be persisted.",
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        )
      }
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

  private fun runDeclaredValidationGateCycle(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ): PhaseOutcome {
    val validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT
    val changedPaths = validationChangedPaths(state)
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
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, repairIteration ->
          launchValidationGateRepair(
            run = run,
            state = state,
            iteration = iteration,
            observability = observability,
            phaseTokenAccumulator = phaseTokenAccumulator,
            findings = findings,
            repairTurn = repairIteration,
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

  @Suppress("LongParameterList")
  private fun launchValidationGateRepair(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
    findings: ValidationFindingSetProjection,
    repairTurn: Int,
  ): ValidationGateAgentRepairResult {
    // The phase attempt deliberately does NOT advance across repair turns for the durable watermark:
    // charging honest gate-finding repairs to the phase semantic budget would block runs early. Schema-
    // invalid *repair receipts* still earn a bounded corrective re-launch inside this turn, carrying
    // the rejected body via PriorAttemptCorrection — the same contract as every other fix-loop phase.
    val repairRun = run.copy(validationGateFindings = findings, validationGateRepairTurn = repairTurn)
    var priorCorrection: PriorAttemptCorrection? = null
    var attemptIteration = iteration
    var outputGateFailures = 0
    var result: ValidationGateAgentRepairResult? = null
    while (result == null) {
      val attempt = attemptOnce(
        repairRun,
        state,
        attemptIteration,
        observability,
        priorCorrection,
        phaseTokenAccumulator,
      )
      val settled = attempt.settledOutcome
      val completed = settled?.completedOutput
      when {
        completed != null -> result = ValidationGateAgentRepairResult.Completed(completed)
        settled != null -> result = ValidationGateAgentRepairResult.Blocked(
          settled.blockedReason
            ?: settled.pausedReason
            ?: "Validation repair attempt blocked.",
        )
        attempt.malformedOutput || attempt.schemaInvalidRetryReason != null -> {
          var blockedReason: String? = null
          outputGateFailures += 1
          FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(
            run.phaseId,
            outputGateFailures,
          )?.let { formatBlock ->
            blockedReason = withSchemaGateDetail(
              formatBlock,
              requireNotNull(attempt.schemaInvalidOperatorReason),
            )
          }
          if (blockedReason != null) {
            result = ValidationGateAgentRepairResult.Blocked(blockedReason)
          } else {
            priorCorrection = PriorAttemptCorrection.schemaGate(
              requireNotNull(attempt.schemaInvalidRetryReason),
              correctiveRepairContext = attempt.correctiveRepairContext,
            )
            attemptIteration += 1
          }
        }
        else -> result = ValidationGateAgentRepairResult.Completed(
          FeatureTaskRuntimePhaseOutput(
            phaseId = run.phaseId,
            iteration = iteration,
            payload =
            """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"validate",""" +
              """"status":"completed","summary":"Gate repair segment.","produced_outputs":{}}""",
          ),
        )
      }
    }
    return requireNotNull(result)
  }

  private fun settleRuntimeOwnedValidation(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = runCatching {
      outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
    }.getOrElse { error ->
      return blockAndPersistInPhase(
        run,
        iteration,
        "Runtime-owned validation settlement did not validate: ${error.message.orEmpty()}",
        observability,
      )
    }
    val normalizedOutput = acceptedOutput.normalizedOutput
    val persisted = recorder.recordCompletedPhase(
      phaseStateRequest(
        run,
        iteration,
        STATUS_COMPLETED,
        finished = true,
        outputArtifact = outputText,
        normalizedOutput = normalizedOutput,
        repairEvidence = acceptedOutput.repairEvidence,
      ),
      run.request.dbPathOverride,
    )
    if (!persisted) {
      return blockAndPersistInPhase(
        run,
        iteration,
        "Runtime-owned validation settlement could not be persisted.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
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

  private fun validationChangedPaths(state: FeatureTaskRuntimeRunState): List<String> {
    val implement = state.outputs().lastOrNull {
      it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    } ?: return emptyList()
    val envelope = JsonSupport.parseObjectOrNull(implement.payload)?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return emptyList()
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
    val receipt = JsonSupport.anyToStringAnyMap(produced["implementation_receipt"])
      ?: produced
    return (receipt["changed_paths"] as? List<*>)?.filterIsInstance<String>().orEmpty()
  }

  private fun packCollectAllCommand(run: PhaseRun, state: FeatureTaskRuntimeRunState): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE || run.agentRunValidateFallback) {
      return null
    }
    return when (val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths(state))) {
      is ValidationGateResolution.Declared -> resolution.declaration.collectAllFullGateCommand.joinToString(" ")
      else -> null
    }
  }

  private fun runPhaseAttempts(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var iteration = state.nextIteration(run.phaseId)
    // Continuation segments advance the persisted attempt watermark like any other attempt, but they
    // stay on their own uncapped axis. In-process the two stay separate because settleIncompleteWork
    // never advances semanticIteration; across a process boundary only the watermark survives, so
    // without discounting the durable segments a resume would charge honest continuation work to the
    // semantic fix loop and block a run that never emitted invalid output. Read before the entry
    // check for exactly that reason.
    val continuationSegmentCount = durableContinuationSegmentCount(run)
    // Attempts that ended before the output gate — a dead process, or a launch the provider refused
    // at a usage limit — are discounted for the same reason continuation segments are: they emitted
    // nothing to repair, so charging them to the semantic budget both spends repair attempts on
    // unrepairable failures and reports the eventual block as invalid output. Only the failures are
    // charged to the process budget; a provider pause is not a failure of this run.
    val nonOutputAttempts = durableNonOutputAttempts(run)
    val processFailures = nonOutputAttempts.filterNot(FeatureTaskRuntimeNonOutputAttempt::paused)
    // An operator who explicitly reopened this phase has replaced every budget with their own
    // decision. Restart the baseline so the reopened phase actually relaunches instead of
    // re-surfacing the block the operator just acted on.
    val operatorReopened = operatorReopenedPhase(run.phaseId)
    if (operatorReopened) state.restartAttemptBudget(run.phaseId)
    // Clamped because a re-entry baseline may already have absorbed the same watermark the segment
    // count discounts; double-discounting must not drive the semantic index below its first attempt.
    val semanticIteration = (
      state.fixLoopIterationFor(run.phaseId, iteration) - continuationSegmentCount - nonOutputAttempts.size
      ).coerceAtLeast(1)
    if (!operatorReopened) {
      FeatureTaskRuntimeAttemptBudgets
        .processFailureBlockReason(run.phaseId, processFailures.size, processFailures.lastOrNull()?.reason)
        ?.let { reason ->
          return blockAndPersistInPhase(
            run,
            iteration,
            reason,
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          )
        }
    }
    val crashResumed = state.resumedFromPriorProcess(run.phaseId)
    state.recordPhaseLaunched(run.phaseId)
    observability.started(
      run.phaseId,
      agentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry(
        resumed = iteration > 1 || state.hasPriorRecord(run.phaseId),
        startKind = featureTaskRuntimeStartContinuationKind(
          crashResumed = crashResumed,
          verifierReentry = run.reentry?.let { isLoopDestination(it) } == true,
          attemptCount = iteration,
        ),
      ),
    )
    var outcome: PhaseOutcome? = null
    val loop = PhaseAttemptLoopState(
      iteration = iteration,
      malformedAttemptCount = 0,
      outputGateFailures = 0,
      semanticIteration = semanticIteration,
      continuationSegmentCount = continuationSegmentCount,
    )
    while (outcome == null) {
      val attempt = attemptOnce(run, state, loop.iteration, observability, loop.priorCorrection, phaseTokenAccumulator)
      val context = FixLoopBranchContext(run, attempt, loop, observability, agentId)
      outcome = attempt.settledOutcome ?: when {
        attempt.incompleteWorkContinuationReason != null -> settleIncompleteWork(context)
        attempt.malformedOutput -> settleMalformedOutput(context)
        // Its own branch, not the semantic-schema one: a retryable blocked/failed envelope is
        // schema-VALID, so prompting it with the schema-correction directive, reporting its block as a
        // schema-gate failure, or dispositioning it INVALID_OUTPUT would all misdescribe it.
        attempt.retryableTerminalRetryReason != null -> settleRetryableTerminal(context)
        // Before the semantic branch and after the terminal one: a receipt that still owes findings
        // is schema-valid, so charging it to the output-gate budget would block the round for work it
        // can still finish.
        attempt.findingsOwedKind != null -> settleFindingsOwed(context)
        else -> settleSemanticFailure(context)
      }
    }
    return outcome
  }

  /** Mutable per-phase fix-loop bookkeeping, held together so the branch handlers can advance it. */
  private class PhaseAttemptLoopState(
    var iteration: Int,
    var malformedAttemptCount: Int,
    var outputGateFailures: Int,
    var semanticIteration: Int,
    var continuationSegmentCount: Int,
    var priorCorrection: PriorAttemptCorrection? = null,
    var priorUnaccountedFindings: Set<String>? = null,
    var priorUnresolvedFindings: Set<String> = emptySet(),
    var itemCoverageSegmentCount: Int = 0,
  )

  /** Everything a fix-loop branch handler reads; they only ever travel as a set. */
  private data class FixLoopBranchContext(
    val run: PhaseRun,
    val attempt: AttemptResult,
    val loop: PhaseAttemptLoopState,
    val observability: FeatureTaskRuntimeRunObservability,
    val agentId: String,
  )

  private fun settleIncompleteWork(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    loop.continuationSegmentCount += 1
    if (!recordIncompleteAttempt(run, loop.iteration, attempt)) {
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        "Feature-task-runtime phase '${run.phaseId}' could not durably append its incomplete " +
          "implementation attempt (segment ${loop.continuationSegmentCount}). Continuing would lose the " +
          "continuation projection, so the run stops here rather than retrying against state that was " +
          "never persisted.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        fileManifest = attempt.fileManifest,
      )
    }
    loop.iteration += 1
    // This attempt was schema-VALID and merely incomplete, so any correction carried from an
    // earlier malformed attempt is now stale. Leaving it set would hand the next segment both the
    // continuation directive and a schema-rejection directive naming a reason from two attempts
    // ago, telling the agent its valid output was rejected by the schema gate.
    loop.priorCorrection = null
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.continuationSegmentCount,
      FeatureTaskRuntimeContinuationKind.IMPLEMENTATION_CONTINUATION,
    )
    return null
  }

  /**
   * Sends the round back for the findings it still owes, or blocks when the owed set stopped moving.
   *
   * Both budgets are counted in finding references rather than attempts, which is what keeps a round
   * from being blocked while it still has real repair work left. An omitted finding must be accounted
   * for on the next attempt; a finding reported unresolved gets one more fix attempt and then belongs
   * to an operator.
   */
  private fun settleFindingsOwed(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    val refs = requireNotNull(attempt.findingsOwedRefs)
    val blockReason = when (requireNotNull(attempt.findingsOwedKind)) {
      FindingsOwedKind.OMITTED -> FeatureTaskRuntimeAttemptBudgets.findingCoverageBlockReason(
        run.phaseId,
        refs,
        loop.priorUnaccountedFindings,
      )
      FindingsOwedKind.UNRESOLVED -> FeatureTaskRuntimeAttemptBudgets.unresolvedFindingBlockReason(
        run.phaseId,
        refs,
        loop.priorUnresolvedFindings,
        requireNotNull(attempt.findingsOwedDetail),
      )
    }
    blockReason?.let { reason ->
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        reason,
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        fileManifest = attempt.fileManifest,
      )
    }
    when (attempt.findingsOwedKind) {
      FindingsOwedKind.OMITTED -> loop.priorUnaccountedFindings = refs
      FindingsOwedKind.UNRESOLVED -> loop.priorUnresolvedFindings = loop.priorUnresolvedFindings + refs
      null -> Unit
    }
    loop.itemCoverageSegmentCount += 1
    loop.iteration += 1
    loop.priorCorrection = PriorAttemptCorrection.unaccountedFindings(
      requireNotNull(attempt.findingsOwedRetryReason),
    )
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.itemCoverageSegmentCount,
      FeatureTaskRuntimeContinuationKind.ITEM_COVERAGE,
    )
    return null
  }

  private fun settleMalformedOutput(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    loop.outputGateFailures += 1
    loop.malformedAttemptCount += 1
    val formatBlock = FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(
      run.phaseId,
      loop.outputGateFailures,
    )
    if (formatBlock == null) {
      loop.iteration += 1
      loop.priorCorrection = PriorAttemptCorrection.schemaGate(
        requireNotNull(attempt.schemaInvalidRetryReason),
        correctiveRepairContext = attempt.correctiveRepairContext,
      )
      observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, loop.malformedAttemptCount)
      return null
    }
    return blockAndPersistInPhase(
      run,
      loop.iteration,
      withSchemaGateDetail(formatBlock, requireNotNull(attempt.schemaInvalidOperatorReason)),
      observability,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
      fileManifest = attempt.fileManifest,
      rejectedOutput = attempt.rejectedOutput,
    )
  }

  /**
   * A retryable `blocked` or `failed` envelope re-entering the loop as itself.
   *
   * It shares the semantic budget with schema-invalid retries but nothing else: the prompt gets the
   * terminal-retry directive rather than the schema-correction one, the block reason is not wrapped in
   * the schema-gate preamble, the block carries the envelope's own disposition instead of
   * INVALID_OUTPUT, and the re-entry is stamped PROCESS_RETRY so the AC-009 status and telemetry
   * surfaces do not report a schema correction that never happened.
   */
  private fun settleRetryableTerminal(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        "${nonRetryingPhaseSchemaBlockReason(run.phaseId)} ${requireNotNull(attempt.retryableOperatorReason)}",
        observability,
        failureDisposition = requireNotNull(attempt.retryableTerminalDisposition),
        fileManifest = attempt.fileManifest,
      )
    }
    val failedIteration = loop.semanticIteration
    loop.iteration += 1
    loop.semanticIteration += 1
    loop.priorCorrection =
      PriorAttemptCorrection.retryableTerminal(requireNotNull(attempt.retryableTerminalRetryReason))
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      failedIteration,
      FeatureTaskRuntimeContinuationKind.PROCESS_RETRY,
    )
    return null
  }

  private fun settleSemanticFailure(context: FixLoopBranchContext): PhaseOutcome? {
    val (run, attempt, loop, observability, agentId) = context
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        withSchemaGateDetail(
          nonRetryingPhaseSchemaBlockReason(run.phaseId),
          requireNotNull(attempt.retryableOperatorReason),
        ),
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
        fileManifest = attempt.fileManifest,
        rejectedOutput = attempt.rejectedOutput,
      )
    }
    loop.outputGateFailures += 1
    FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(run.phaseId, loop.outputGateFailures)?.let { capReason ->
      return blockAndPersistInPhase(
        run,
        loop.iteration,
        withSchemaGateDetail(capReason, requireNotNull(attempt.retryableOperatorReason)),
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
        fileManifest = attempt.fileManifest,
        rejectedOutput = attempt.rejectedOutput,
      )
    }
    val failedIteration = loop.semanticIteration
    loop.iteration += 1
    loop.semanticIteration += 1
    loop.priorCorrection = attempt.semanticRetryReason?.let { retryReason ->
      PriorAttemptCorrection.schemaGate(
        retryReason,
        correctiveRepairContext = attempt.correctiveRepairContext,
      )
    }
    observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, failedIteration)
    return null
  }

  /**
   * Continuation segments already spent on this phase, read from the durable attempt history rather
   * than an in-memory counter. Without this a crash resume would silently refill the budget and the
   * bounded continuation loop would not be bounded across process lifetimes.
   *
   * Scoped to this visit — phase, loop AND edge iteration — matching the continuation projection.
   * Counting earlier rounds of the same loop would charge a brand-new repair round for segments spent
   * on work it was never given, and could block it before its first launch.
   */
  /**
   * The attempts this phase has spent in a row without reaching its output gate, read from the
   * durable ledger so the count survives the crash resume that produced it. Without this the outer
   * resume path charges each relaunch to the semantic repair budget, and a phase that never emitted
   * a byte gets blocked for "invalid output".
   */
  private fun durableNonOutputAttempts(run: PhaseRun): List<FeatureTaskRuntimeNonOutputAttempt> =
    state.trailingNonOutputAttempts(run.phaseId) { reason -> isProcessFailureBlockReason(run.phaseId, reason) }

  /**
   * True while an operator-reopened phase has not yet run. An operator who reopened a blocked phase
   * has substituted their own judgment for every automatic budget, so the reopened phase must
   * actually relaunch — re-surfacing the block they just acted on makes the reopen a no-op.
   */
  private fun operatorReopenedPhase(phaseId: String): Boolean =
    operatorBlockRetry?.phaseId == phaseId && !operatorBlockRetryCompleted

  private fun durableContinuationSegmentCount(run: PhaseRun): Int {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return 0
    val attempts = recorder.loadImplementationAttempts(run.request.workflowId, run.request.dbPathOverride)
      ?: return 0
    return attempts.count {
      it.phaseId == run.phaseId &&
        it.loopId == run.reentry?.loopId &&
        it.edgeIteration == run.reentry?.edgeIteration &&
        it.status == skillbill.workflow.taskruntime.model
          .FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
    }
  }

  /**
   * Appends the incomplete attempt to the durable history, reporting whether it actually landed.
   *
   * A false return must never be swallowed. The continuation projection and the durable segment
   * budget are both derived from this history: a silently dropped append leaves the next segment with
   * no prior receipt AND leaves the segment count at zero, so a crash resume would refill the budget
   * from scratch and the bounded continuation loop would stop being bounded across process lifetimes.
   * Blocking is the only safe response. The ordering fix above removed the one reachable trigger
   * (a non-`implementation_receipt` projection_kind reaching this path); this stays as the
   * defense-in-depth guard for any future empty-patch condition.
   */
  private fun recordIncompleteAttempt(run: PhaseRun, iteration: Int, attempt: AttemptResult): Boolean {
    val normalized = attempt.incompleteWorkOutput ?: return false
    return recorder.recordIncompleteImplementationAttempt(
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

  @Suppress("LongParameterList")
  private fun blockAndPersist(
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
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
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
      outputArtifact = normalizedOutput?.canonicalJson ?: outputArtifact,
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
  private fun pauseAndPersistInPhase(
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

  private fun blockAndPersistInPhase(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    outputArtifact: String? = null,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
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
  private fun settleRecordRejection(
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

  private fun blockUnattributableRecordRejection(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    rejection: RecordRejection,
    producer: String?,
  ): PhaseOutcome {
    val detail = payloadFreeRejectionReason(
      "reconciliation-${rejection.rejectionClass}",
      rejectionPath(rejection.rejectionDetail),
    )
    val rejectedOutput = run.declaration.projectionDeclarations
      .asSequence()
      .map { it.producerIteration.phaseId }
      .distinct()
      .mapNotNull { phaseId -> state.outputFor(phaseId) }
      .firstOrNull()
    val evidence = rejectedOutput?.let { output ->
      val agentId = state.recordFor(output.phaseId)?.resolvedAgentId ?: return@let null
      when (
        val read = recorder.producerOutput(
          request.workflowId,
          output.phaseId,
          output.iteration.coerceAtLeast(1),
          agentId,
          request.dbPathOverride,
          state.evidenceGeneration(output.phaseId),
        )
      ) {
        is FeatureTaskRuntimeProducerOutputRead.Found -> read.evidence
        is FeatureTaskRuntimeProducerOutputRead.Absent,
        is FeatureTaskRuntimeProducerOutputRead.Unreadable,
        -> null
      }
    }
    evidence?.let {
      recordRejectedOutput(
        run = run,
        iteration = it.attempt,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason = retryRejectionReason(detail, rejection.rejectionDetail),
        outputBytes = it.payload ?: byteArrayOf(),
        phaseId = it.phaseId,
        agentId = it.agentId,
        model = it.model,
        path = rejectionPath(rejection.rejectionDetail),
        outputByteSize = it.byteSize,
        outputSha256 = it.sha256,
        outputTruncated = it.payload == null,
        repairTurn = it.repairTurn,
      )
    }
    val reason = if (producer == null) {
      "Feature-task-runtime phase '${run.phaseId}' rejected an upstream durable record " +
        "(${rejection.rejectionClass}) it cannot attribute to a producing phase, so no regeneration edge " +
        "applies; the run blocks durably. Recover the record out of band by deleting or migrating the " +
        "offending row. Detail: $detail"
    } else {
      "Feature-task-runtime phase '${run.phaseId}' rejected the durable record produced by '$producer', but " +
        "'$producer' is absent from this run's resolved pipeline (a goal-continuation truncation dropped it), " +
        "so it cannot be regenerated in-band; the run blocks durably. Recover the record out of band by " +
        "deleting or migrating the offending row. Detail: $detail"
    }
    return blockAndPersistInPhase(
      run,
      iteration,
      reason,
      observability,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      childNeverLaunched = true,
    )
  }

  private fun rejectionPath(detail: String): String {
    Regex("""(?:instance location|path|pointer)\s*[:=]\s*['"]?(/[^\s,'"]*)""", RegexOption.IGNORE_CASE)
      .find(detail)
      ?.groupValues
      ?.get(1)
      ?.let { return it }
    val dollarPath = Regex("""\$(?:\.[A-Za-z0-9_-]+|\[[0-9]+])+""").find(detail)?.value ?: return "/"
    return dollarPath.removePrefix("$")
      .replace(Regex("""\.([A-Za-z0-9_-]+)"""), "/${'$'}1")
      .replace(Regex("""\[([0-9]+)]"""), "/${'$'}1")
  }

  private fun payloadFreeRejectionReason(rule: String, path: String): String =
    "Rejected output violated '$rule' at '$path'. Inspect the private diagnostic for the exact response."

  /**
   * The retry-facing counterpart of [payloadFreeRejectionReason]. A producer cannot repair an output from a
   * rule name and a path alone, so the validator's constraint text — the violated rule, the expected shape
   * and the offending field, all authored from the schema and never from the response — is appended for the
   * next prompt and for the private diagnostic row. The payload-free sentence stays the prefix so both
   * readers still learn where the raw response is kept.
   *
   * A null or blank [validationReason] means the producing seam had no value-free restatement to offer, so
   * the payload-free sentence stands alone; the value-bearing variant is never substituted in its place.
   */
  private fun retryRejectionReason(payloadFreeReason: String, validationReason: String?): String =
    if (validationReason.isNullOrBlank()) {
      payloadFreeReason
    } else {
      "$payloadFreeReason Violated constraint: ${boundedSchemaGateDetail(validationReason)}"
    }

  /**
   * Semantic-gate detail that is safe to place outside the authorized repair section.
   *
   * Mutating-reconciliation is a fixed template. Producer/consumer projection and output-verification
   * may carry schema-structure text the producer needs, but only after response-derived dumps
   * (quoted wire verdicts, offending-value appendices, expected=/actual= receipt lists) are scrubbed.
   * Audit ledger/repair gates stay null except for scrubbed bounded artifact_ref/check_ref
   * constraints — those must reach the retry reason so compound or oversized refs get actionable
   * guidance instead of a generic audit sentence alone.
   */
  private fun payloadFreeSemanticGateConstraint(
    rule: String,
    detail: String,
    rejectedOutput: Map<String, Any?>,
  ): String? = when (rule) {
    "mutating-reconciliation" -> detail.takeUnless { it.isBlank() }
    "repair-receipt" -> detail.takeUnless { it.isBlank() }
    "producer-projection",
    "consumer-projection",
    "output-verification",
    -> scrubResponseDerivedGateDetail(detail, rejectedOutput)
    else -> scrubBoundedReferenceGateConstraint(detail)
  }

  /**
   * Extracts a payload-free bounded-reference constraint from semantic-gate detail. Returns null when
   * the detail does not name artifact_ref or check_ref, so audit identifiers and expected=/actual=
   * receipt lists never reach the retry reason by themselves.
   */
  private fun scrubBoundedReferenceGateConstraint(detail: String): String? {
    if (detail.isBlank()) return null
    val namesArtifactRef = detail.contains("artifact_ref")
    val namesCheckRef = detail.contains("check_ref")
    if (!namesArtifactRef && !namesCheckRef) return null
    val cap = BOUNDED_REF_LENGTH_CAP_PATTERN.find(detail)?.groupValues?.get(1)?.replace(",", "")
    return when {
      namesArtifactRef && cap != null -> "artifact_ref allows at most $cap characters."
      namesCheckRef && cap != null -> "check_ref allows at most $cap characters."
      namesArtifactRef ->
        "artifact_ref must be a bounded path or symbol reference such as " +
          "src/main/Example.kt or src/main/Example.kt:Example."
      else ->
        "check_ref must match AC-###, F-###, or a name ending in Test or Check, optionally followed " +
          "by :symbol; examples: AC-005, FeatureTaskRuntimeAuditEntryGateTest, or codeCheck:detekt."
    }
  }

  /**
   * Strips known response-value dumps from semantic-gate detail before it can enter a retry prompt
   * outside the authorized repair section. Schema-structure fragments (property names, found/expected
   * types, maxLength caps) remain so length and shape corrections still fire.
   *
   * Caps at [SCHEMA_GATE_DETAIL_MAX_CHARS] before pattern work so an oversized wire verdict cannot
   * amplify retry CPU; when the cap cuts inside a quoted verdict, [scrubOffVocabularyVerdictQuote]
   * strips the open marker through end rather than leaving a partial response-derived quote.
   */
  private fun scrubResponseDerivedGateDetail(detail: String, rejectedOutput: Map<String, Any?>): String? {
    if (detail.isBlank()) return null
    var text = detail.take(SCHEMA_GATE_DETAIL_MAX_CHARS)
    text = scrubOffVocabularyVerdictQuote(text)
    text = OFFENDING_VALUE_APPENDIX_PATTERN.replace(text, "")
    text = EXPECTED_ACTUAL_LIST_PATTERN.replace(text, "")
    responseStringValues(rejectedOutput)
      .filter { value ->
        value.length >= MIN_RESPONSE_STRING_VALUE_LENGTH &&
          SCHEMA_DETAIL_TYPE_WORDS.none { typeWord -> typeWord.equals(value, ignoreCase = true) } &&
          text.contains(value)
      }
      .sortedByDescending(String::length)
      .forEach { value -> text = text.replace(value, "[response value omitted]") }
    return text.trim().takeUnless { it.isBlank() }
  }

  private fun responseStringValues(value: Any?): List<String> {
    val values = mutableListOf<String>()
    collectResponseStringValues(value, values)
    return values.distinct()
  }

  private fun collectResponseStringValues(value: Any?, values: MutableList<String>) {
    when (value) {
      is String -> values += value
      is Map<*, *> -> value.values.forEach { nested -> collectResponseStringValues(nested, values) }
      is Iterable<*> -> value.forEach { nested -> collectResponseStringValues(nested, values) }
    }
  }

  @Suppress("LongParameterList")
  private fun attemptOnce(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    priorCorrection: PriorAttemptCorrection?,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): AttemptResult {
    // The running write is what the IDE reads as current_model while the child is in flight, so it
    // stamps the directive the launch below is rendered from. The settling exits then clear it only
    // where the launch proved no child ever ran, via LaunchResult.childNeverLaunched.
    persistPhase(
      run,
      iteration,
      STATUS_RUNNING,
      finished = false,
      outputArtifact = null,
      launched = launchedModelDirective(run),
    )
    val launch = launchAndCapture(run, state, priorCorrection, phaseTokenAccumulator)
    launch.providerLimitReason?.let { reason ->
      return AttemptResult.settled(pauseAndPersistInPhase(run, iteration, reason, observability, launch.fileManifest))
    }
    launch.infraFailureReason?.let { reason ->
      return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = launch.failureDisposition,
          fileManifest = launch.fileManifest,
          childNeverLaunched = launch.childNeverLaunched,
        ),
      )
    }
    launch.recordRejection?.let { rejection ->
      return AttemptResult.settled(
        settleRecordRejection(run, state, iteration, observability, rejection),
      )
    }
    val fileManifest = requireNotNull(launch.fileManifest)
    return gateOutput(
      run,
      iteration,
      requireNotNull(launch.capturedStdout),
      requireNotNull(launch.capturedStdoutBytes),
      launch.capturedStdoutTruncated,
      requireNotNull(launch.capturedStdoutByteSize),
      requireNotNull(launch.capturedStdoutSha256),
      observability,
      fileManifest,
    )
  }

  private fun gateOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    outputBytes: ByteArray,
    outputTruncated: Boolean,
    outputByteSize: Long,
    outputSha256: String,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult = try {
    val acceptedOutput = outputValidator
      .validatePhaseOutput(outputText, sourceLabel = run.phaseId)
      .requireAcceptedOutput(run.phaseId)
    settleValidatedOutput(
      run, iteration, acceptedOutput.normalizedOutput, acceptedOutput.repairEvidence, observability, fileManifest,
      outputText, outputBytes, outputTruncated, outputByteSize, outputSha256,
    )
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
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

  /**
   * Builds the in-flight corrective-repair context from the same capture metadata and diagnostic
   * identity just recorded for this rejection. Truncated captures stay payload-free (digest/bytes
   * only); an unchanged body within budget can carry Exact for the authorized repair projection.
   */
  private fun correctiveRepairContextForRejection(
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
    structuralRepairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence? =
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

  private fun recordRejectedOutput(
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

  @Suppress("ReturnCount")
  private fun settleValidatedOutput(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
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
    auditRepairNonProgressReason(run, outputMap, repositoryFingerprint)?.let { reason ->
      return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          fileManifest = fileManifest,
          normalizedOutput = attested,
          repairEvidence = repairEvidence,
        ),
      )
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
      attested,
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

  private class ValidatedOutputCapture(
    val run: PhaseRun,
    val iteration: Int,
    val outputText: String,
    val outputBytes: ByteArray,
    val outputTruncated: Boolean,
    val outputByteSize: Long,
    val outputSha256: String,
    val repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
    val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  )

  private fun rejectValidatedOutput(
    capture: ValidatedOutputCapture,
    outputMap: Map<String, Any?>,
    rule: String,
    detail: String,
  ): AttemptResult {
    val diagnosticRule = rule
    val path = rejectionPath(detail)
    val reason = payloadFreeRejectionReason(rule, path)
    // Only scrubbed semantic templates reach the retry reason. Response-derived dumps stay in the
    // private diagnostic and the authorized repair body.
    val retryFacingConstraint = payloadFreeSemanticGateConstraint(rule, detail, outputMap)
    val retryReason = retryRejectionReason(reason, retryFacingConstraint)
    val diagnosticWrite = recordRejectedOutput(
      capture.run, capture.iteration, diagnosticRule, detail, capture.outputBytes, path = path,
      outputTruncated = capture.outputTruncated,
      outputByteSize = capture.outputByteSize,
      outputSha256 = capture.outputSha256,
    )
    // Semantic/schema rejection after a successful parse: rebuild the repair context from the same
    // capture that was just recorded, using only payload-free constraint text so value-bearing detail
    // stays out of the typed context and out of the next prompt outside the repair section.
    return schemaInvalidAttempt(
      reason,
      capture.fileManifest,
      retryReason = retryReason,
      correctiveRepairContext = correctiveRepairContextForRejection(
        run = capture.run,
        iteration = capture.iteration,
        outputText = capture.outputText,
        outputTruncated = capture.outputTruncated,
        outputByteSize = capture.outputByteSize,
        outputSha256 = capture.outputSha256,
        diagnosticWrite = diagnosticWrite,
        rejectionRule = diagnosticRule,
        rejectionPath = path,
        payloadFreeConstraint = retryFacingConstraint ?: reason,
        // Semantic rejection after AcceptedAfterRepair: syntax repair succeeded earlier; the phase
        // schema or semantic gate still rejected the post-capture response.
        acceptedAfterStructuralRepair = capture.repairEvidence != null,
        structuralRepairEvidence = capture.repairEvidence,
      ),
    )
  }

  private fun retainSettledProducerOutput(capture: ValidatedOutputCapture) {
    val run = capture.run
    recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = run.phaseId,
        attempt = capture.iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = java.time.Instant.now(),
        byteSize = capture.outputByteSize,
        sha256 = capture.outputSha256,
        payload = capture.outputBytes.takeUnless { capture.outputTruncated },
        generation = state.evidenceGeneration(run.phaseId),
        repairTurn = run.validationGateRepairTurn,
      ),
      run.request.dbPathOverride,
    )
  }

  private sealed interface CommitPushFinalisation

  private data object CommitPushNotApplicable : CommitPushFinalisation

  private data class CommitPushSettled(
    val output: NormalizedFeatureTaskRuntimePhaseOutput,
  ) : CommitPushFinalisation

  private data class CommitPushBlocked(val reason: String) : CommitPushFinalisation

  /**
   * SKILL-190: the runtime performs `commit_push`. The agent's completed envelope contributes the
   * outcome message and the enumerated path set; the staging, amend, sha capture and push below are
   * the runtime's, and the captured post-amend sha is written back into the envelope this phase record
   * persists so `commit_push_result.commit_sha` and the goal-continuation outcome derived from that same
   * record cannot disagree.
   */
  @Suppress("ReturnCount") // each early return is one distinct non-applicable or blocking condition
  private fun finaliseSubtaskCommit(
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): CommitPushFinalisation {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH) {
      return CommitPushNotApplicable
    }
    if (normalizedOutput.envelope["status"] != STATUS_COMPLETED) return CommitPushNotApplicable
    val branch = finalisationBranch() ?: return unownedWorktreeCommitSha(run, normalizedOutput)
    val handoff = when (val read = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(normalizedOutput.envelope)) {
      is FeatureTaskRuntimeCommitPushHandoffInvalid -> return CommitPushBlocked(read.reason)
      is FeatureTaskRuntimeCommitPushHandoffValid -> read.handoff
    }
    val identity = subtaskCommitIdentity()
    val ledger = subtaskCommitLedgerState(identity)
    val outcome = FeatureTaskRuntimeSubtaskFinalisation(
      gitOperations = phaseGates.gitOperations,
      repoRoot = request.repoRoot,
      record = { record -> runCatching { diagnostics.warning(record) } },
      recordCommit = { commitSha, stagedPaths ->
        recordFinalisedCheckpointIdentity(run.phaseId, branch, ledger, commitSha, stagedPaths)
      },
    ).finalise(
      identity = identity,
      durableCommitSha = ledger.commitSha,
      sequenceNumber = ledger.nextSequenceNumber,
      handoff = handoff,
      metadata = FeatureTaskRuntimeCheckpointMetadata(
        phaseId = run.phaseId,
        loopId = null,
        generation = checkpointGeneration(null),
        branch = branch,
        intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
      ),
      manifestCommitSha = goalContinuationManifestCommitSha,
    )
    if (outcome is FeatureTaskRuntimeSubtaskFinalisationBlocked) {
      return CommitPushBlocked(outcome.reason)
    }
    val finalised = outcome as FeatureTaskRuntimeSubtaskFinalised
    return CommitPushSettled(
      revalidated(
        run.phaseId,
        FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(normalizedOutput.envelope, finalised.commitSha),
      ),
    )
  }

  /**
   * The runtime owns no branch here, so it committed nothing and has nothing to amend. Downstream
   * consumers still need a commit sha, so the measured HEAD is published as one and the degradation is
   * recorded: a phase record with no sha at all would fail the `pr` consumer projection and the
   * per-subtask commit invariant alike.
   */
  private fun unownedWorktreeCommitSha(
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): CommitPushFinalisation {
    val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    val sha = head.value.orEmpty().trim().takeIf { head.ok && it.isNotBlank() }
      ?: return CommitPushNotApplicable
    runCatching {
      diagnostics.warning(
        "seam=FeatureTaskRuntimeRunLoop.finaliseSubtaskCommit value_used='measured HEAD $sha' " +
          "value_expected=a runtime-finalised subtask commit for '${request.issueKey}' " +
          "cause=the run has no resolved, unprotected, checked-out branch, so finalisation could not " +
          "stage, amend, or push and the commit sha degrades to whatever HEAD already names",
      )
    }
    return CommitPushSettled(
      revalidated(run.phaseId, FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(normalizedOutput.envelope, sha)),
    )
  }

  /**
   * The branch finalisation may write to: the run's own resolved, unprotected, currently checked-out
   * branch. Anything else means the runtime does not own this working tree, which is the same condition
   * under which no checkpoint ever committed here either.
   */
  private fun finalisationBranch(): String? {
    val branch = resolvedBranch?.takeIf { FeatureTaskRuntimeBranchSetup.protectedBranchName(it) == null }
      ?: return null
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    return branch.takeIf { head.ok && head.value.trim() == branch.trim() }
  }

  /**
   * The decomposition manifest records the post-push commit sha only after the goal runner reconciles a
   * completed child, so finalisation usually sees null here and defers pruning to that boundary.
   */
  private val goalContinuationManifestCommitSha: String? = null

  /**
   * The durable pointer to the finalisation commit, appended between the commit and the push. Returns
   * the blocking reason when it cannot be appended: without the pointer a re-entry after a failed push
   * resolves Create against an already-committed tree and the subtask can never finish, so continuing
   * past a failed append would trade a resumable block for a permanently stuck one.
   */
  private fun recordFinalisedCheckpointIdentity(
    phaseId: String,
    branch: String,
    ledger: SubtaskCommitLedgerState,
    commitSha: String,
    stagedPaths: List<String>,
  ): String? {
    val appended = runCatching {
      recorder.appendCheckpointIdentity(
        workflowId = request.workflowId,
        issueKey = request.issueKey,
        subtaskId = request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
        branch = branch,
        phaseId = phaseId,
        loopId = null,
        generation = checkpointGeneration(null),
        parentSha = ledger.commitSha,
        ownedPaths = stagedPaths,
        commitSha = commitSha,
        dbOverride = request.dbPathOverride,
      )
    }
    if (appended.getOrDefault(false)) return null
    val cause = appended.exceptionOrNull()?.message ?: "the workflow row was absent"
    runCatching {
      diagnostics.warning(
        "seam=FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity " +
          "value_used='no durable identity for finalised commit $commitSha' " +
          "value_expected=an appended checkpoint identity for '${request.issueKey}' " +
          "cause=$cause",
      )
    }
    return "needs_human: the finalised subtask commit '$commitSha' was written but its durable " +
      "checkpoint identity could not be recorded ($cause), so it was not pushed. Without that pointer " +
      "a resumed run would open a second commit for this subtask instead of amending this one. Repair " +
      "the workflow store and resume; the commit is already on the branch."
  }

  private fun revalidated(phaseId: String, envelope: Map<String, Any?>): NormalizedFeatureTaskRuntimePhaseOutput =
    outputValidator
      .validatePhaseOutput(JsonSupport.mapToJsonString(envelope), sourceLabel = phaseId)
      .requireAcceptedOutput(phaseId)
      .normalizedOutput

  /**
   * Packs without `validation_gate` fall back to agent-run validate. That path must never publish
   * agent-authored gate measurements: overwrite (or supply) `gate_run_count`/`gate_runs` with the
   * degradation attestation before consumer projection and persist.
   */
  private fun attestAbsentGateValidationReceipt(
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val eligible = run.agentRunValidateFallback &&
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE &&
      normalizedOutput.envelope["status"] == STATUS_COMPLETED
    if (!eligible) return normalizedOutput
    val produced = JsonSupport.anyToStringAnyMap(normalizedOutput.envelope["produced_outputs"])
      ?.toMutableMap()
      ?: return normalizedOutput
    val validationResult = JsonSupport.anyToStringAnyMap(produced["validation_result"])
      ?.toMutableMap()
      ?: return normalizedOutput
    validationResult["gate_run_count"] = 0
    validationResult["gate_runs"] = emptyList<Any?>()
    produced["validation_result"] = validationResult
    val envelope = normalizedOutput.envelope.toMutableMap()
    envelope["produced_outputs"] = produced
    return outputValidator.validatePhaseOutput(
      JsonSupport.mapToJsonString(envelope),
      sourceLabel = run.phaseId,
    ).requireAcceptedOutput(run.phaseId).normalizedOutput
  }

  /**
   * The obligations this implement launch owes, read from durable runtime-owned records only.
   *
   * Planned task ids come from the delivered executable-plan projection; the audit-repair loop's
   * carried items come from the durable launch briefing. Neither is taken from the implementing
   * agent's own envelope, which is the point: an agent that could name its obligations could satisfy
   * them by naming fewer.
   */
  private fun implementationObligations(run: PhaseRun): FeatureTaskRuntimeImplementationObligations {
    val loopId = run.reentry?.loopId
    val delivered = recorder.loadDeliveredProjections(run.request.workflowId, run.request.dbPathOverride)
      .orEmpty().values
    return FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = featureTaskRuntimePlannedTaskIdsFrom(delivered, run.phaseId),
      carriedRepairItemIds = featureTaskRuntimeCarriedRepairItemIds(emptyList()),
      loopId = loopId,
      edgeIteration = run.reentry?.edgeIteration,
    )
  }

  private fun incompleteImplementationReason(run: PhaseRun, outputMap: Map<String, Any?>): String? {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return null
    return featureTaskRuntimeIncompleteWorkGateReason(run.phaseId, outputMap, implementationObligations(run))
  }

  /**
   * The bounded prior receipt the next continuation segment is given, rebuilt from durable records at
   * the launch seam. Both an in-process retry and a fresh-process resume pass through here, so both
   * derive an identical projection from identical durable state; neither depends on the in-memory
   * prompt thread or on the put()-replaced phase-records artifact.
   */
  private fun implementationContinuationFor(run: PhaseRun): FeatureTaskRuntimeImplementationContinuation? {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return null
    val attempts = recorder.loadImplementationAttempts(run.request.workflowId, run.request.dbPathOverride)
      ?: return null
    return featureTaskRuntimeImplementationContinuationFrom(run.phaseId, attempts, implementationObligations(run))
      ?.takeIf { it.openObligationIds.isNotEmpty() || it.unresolvedItems.isNotEmpty() }
  }

  /**
   * The structural contract a phase claiming completion owes its consumer, as the first failing rule.
   *
   * Grouped so the settle function reads as one structural-gate step: these three share a disposition
   * (all route through the SKILL-153 reject path and its bounded cap) and an ordering constraint (all
   * run before the semantic incompleteness gate, so a repairable contract defect is named to the agent
   * rather than burning continuation segments).
   */
  private fun completionProjectionRejection(
    run: PhaseRun,
    iteration: Int,
    outputMap: Map<String, Any?>,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
    repositoryFingerprint: String?,
  ): Pair<String, String>? = producerProjectionGateReason(
    run.phaseId,
    outputMap,
    planningProjectionValidator,
    allowDecompositionPackage = true,
  )?.let { "producer-projection" to it }
    ?: immediateConsumerProjectionGateReason(
      run,
      iteration,
      normalizedOutput,
      repairEvidence,
      repositoryFingerprint,
    )?.let { "consumer-projection" to it }
    ?: outputVerificationGateReason(run.phaseId, outputMap)?.let { "output-verification" to it }

  private fun firstValidatedOutputRejection(phaseId: String, outputMap: Map<String, Any?>): Pair<String, String>? =
    mutatingReconciliationGateReason(phaseId, outputMap)?.let { "mutating-reconciliation" to it }

  /**
   * A completed producer must satisfy the exact projection its immediate forward consumer will parse.
   * This shares the launch assembler and validator instead of restating receipt shapes. Rejecting here
   * keeps malformed finalization receipts in the producer's bounded correction loop.
   */
  private fun immediateConsumerProjectionGateReason(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
    repositoryFingerprint: String?,
  ): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) return null
    // Gate-repair segments are not the validate→write_history handoff. They must not invent
    // gate_run_count/gate_runs; the coordinator re-runs the gate and settleRuntimeOwnedValidation
    // publishes the measured receipt. Matching persistAcceptedOutput's skip for the same flag.
    if (run.validationGateFindings != null) return null
    val producerIndex = transitions.forwardPhaseIds.indexOf(run.phaseId)
    if (producerIndex < 0 || producerIndex == transitions.forwardPhaseIds.lastIndex) return null
    val consumerPhaseId = transitions.forwardPhaseIds[producerIndex + 1]
    val declaration = phaseDeclaration(consumerPhaseId, run.request.runInvariants.featureSize)
    val currentOutput = FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload = normalizedOutput.canonicalJson,
      normalizedOutput = normalizedOutput,
      repairEvidence = repairEvidence,
    )
    val outputs = state.outputs().filterNot { it.phaseId == run.phaseId } + currentOutput
    val resolvedFingerprint = repositoryFingerprint?.takeIf(String::isNotBlank)
      ?: gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)
    val checkpoint = resolvedFingerprint
      ?.let(::FeatureTaskRuntimeRepositoryCheckpoint)
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = declaration,
      runInvariants = run.request.runInvariants,
      recordedOutputs = outputs,
      repositoryCheckpoint = checkpoint,
      expectedRepositoryCheckpoint = checkpoint,
      branchIdentity = resolvedBranch,
      baseBranch = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
        ?.baseBranch
        ?: "main",
    )
    return try {
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
        run.request.workflowId,
        planningProjectionValidator,
        run.request.agentAddonSelection,
      )
      null
    } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
      "Phase '${run.phaseId}' reported 'completed' but its output cannot satisfy immediate consumer " +
        "'$consumerPhaseId': ${boundedSchemaGateDetail(error.message.orEmpty())}"
    } catch (error: InvalidFeatureTaskRuntimePhaseBriefingFramingError) {
      "Phase '${run.phaseId}' reported 'completed' but its output cannot frame immediate consumer " +
        "'$consumerPhaseId': ${boundedSchemaGateDetail(error.message.orEmpty())}"
    }
  }

  private fun recordedFindingVerdictsForFixHandoff(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): List<ReviewFindingVerdict> {
    if (
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX &&
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    ) {
      return emptyList()
    }
    val review = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) ?: return emptyList()
    val envelope = review.normalizedOutput?.envelope
      ?: JsonSupport.parseObjectOrNull(review.payload)
        ?.let { JsonSupport.jsonElementToValue(it) }
        ?.let(JsonSupport::anyToStringAnyMap)
      ?: return emptyList()
    return recorder.recordedFindingVerdicts(envelope, request.dbPathOverride)
  }

  /**
   * The shared review evidence for this launch, or null when the phase declares none or nothing is
   * resolvable. Only the phases that declare the projection pay for the resolution.
   */
  private fun resolveSharedReviewEvidence(
    run: PhaseRun,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
    val declared = run.declaration.projectionDeclarations.any {
      it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
    }
    if (!declared) return null
    return FeatureTaskRuntimeSharedReviewEvidenceResolver(
      phaseGates.sharedEvidenceResolver,
      phaseGates.diffResolver,
    ).resolve(run.request.repoRoot, run.request.workflowId, checkpoint, run.phaseId)
  }

  /**
   * Resolves a repository checkpoint only when some declaration actually needs one, reusing the same
   * `WorkflowGitOperations` fingerprint the audit-repair path already depends on. No new git port is
   * introduced and the domain stays git-agnostic: the checkpoint arrives as a plain value.
   */
  private fun resolveRepositoryCheckpoint(run: PhaseRun): FeatureTaskRuntimeRepositoryCheckpoint? =
    if (run.declaration.projectionDeclarations.none { projection ->
        projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
      }
    ) {
      null
    } else {
      buildRepositoryCheckpoint(run)
    }

  private fun buildRepositoryCheckpoint(run: PhaseRun): FeatureTaskRuntimeRepositoryCheckpoint? {
    val resolvedBranch = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    val goalReviewState = goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
    val revisions = resolveCheckpointRevisions(
      run = run,
      headRevision = resolvedBranch?.branch?.takeIf(String::isNotBlank) ?: "HEAD",
      baseRevision = goalReviewState?.reviewBaseSha ?: resolvedBranch?.reviewBaseSha,
    ) ?: return null
    val ownedPaths = resolveCheckpointOwnedPaths(
      run = run,
      persistedOwnedPaths = resolvedBranch?.workflowOwnedPaths,
      baselineOwnedPaths = resolvedBranch?.baselineOwnedPaths
        ?: goalReviewState?.baselineUntrackedPaths
        ?: resolvedBranch?.baselineUntrackedPaths.orEmpty(),
      revisions = revisions,
    ) ?: return null
    val fingerprint = gitOperations.repositoryCheckpointFingerprint(
      run.request.repoRoot,
      revisions.base,
      revisions.head,
      ownedPaths,
    ).takeIf { it.ok }?.value?.takeIf(String::isNotBlank) ?: return null
    return FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = fingerprint,
      baseRef = revisions.base,
      headRef = revisions.head,
      workingTreeOwnedPaths = ownedPaths,
    )
  }

  private fun resolveCheckpointOwnedPaths(
    run: PhaseRun,
    persistedOwnedPaths: List<String>?,
    baselineOwnedPaths: List<String>,
    revisions: CheckpointRevisions,
  ): List<String>? {
    val workingTreePaths = checkpointOwnedPaths(run, baselineOwnedPaths) ?: return null
    val committedPaths = revisions.base?.let { base ->
      gitOperations.runtimePhaseChangedPathsBetweenCommits(run.request.repoRoot, base, revisions.head)
        .takeIf { it.ok }
        ?.value
        ?.let(FeatureTaskRuntimePhaseSafetyPolicy::lineSeparatedPaths)
        ?: return null
    }.orEmpty()
    // Before a checkpoint has decided ownership the working tree is the only listing there is, so it
    // bootstraps the scope. Once a checkpoint has decided, that decision bounds the scope — it already
    // absorbed what the writing phases wrote, so nothing of this run's work is dropped, and ambient
    // dirt can no longer shift the digest a consumer compares against.
    val durableInventory = persistedOwnedPaths.orEmpty().filter(String::isNotBlank)
    val discovered = if (checkpointOwnershipDecided && durableInventory.isNotEmpty()) {
      durableInventory
    } else {
      (durableInventory + workingTreePaths).distinct()
    }
    val inventory = reconcileCheckpointPathInventory(
      repoRoot = run.request.repoRoot,
      issueKey = run.request.issueKey,
      specReference = run.request.runInvariants.specReference,
      paths = (discovered + committedPaths).distinct(),
    ).sorted()
    return inventory.takeIf {
      recorder.recordWorkflowOwnedPaths(
        run.request.workflowId,
        inventory,
        run.request.dbPathOverride,
      )
    }
  }

  private fun resolveCheckpointRevisions(
    run: PhaseRun,
    headRevision: String,
    baseRevision: String?,
  ): CheckpointRevisions? {
    val immutableHead = gitOperations.resolveCommit(run.request.repoRoot, headRevision)
      .takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
      ?: gitOperations.headCommitSha(run.request.repoRoot).takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
      ?: return null
    val immutableBase = baseRevision?.let { revision ->
      gitOperations.resolveCommit(run.request.repoRoot, revision)
        .takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
        ?: revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) }
    }
    if (baseRevision != null && immutableBase == null) return null
    return CheckpointRevisions(base = immutableBase, head = immutableHead)
  }

  private data class CheckpointRevisions(
    val base: String?,
    val head: String,
  )

  /**
   * Owned paths for the checkpoint scope. Subtracting the run's persisted tracked-and-untracked
   * ownership baseline keeps sibling and pre-existing changes out of this workflow's checkpoint.
   *
   * Both sides of that subtraction come from the same NUL-delimited plumbing listing. `git status
   * --porcelain` is deliberately not the source: it collapses a wholly-untracked directory to one
   * `dir/` entry and C-quotes non-ASCII paths, so a sibling subtask's new directory would never match
   * the `ls-files`-written baseline and would leak into the child's audit scope (AC-014).
   *
   * Returns null when the listing cannot be measured. An empty inventory is a real answer — the scope
   * owns nothing — and an audit reading it concludes no work exists here, so an unmeasurable read must
   * not be able to produce it. The caller drops the whole checkpoint, matching how an unmeasurable
   * fingerprint already blocks the launch instead of degrading it.
   *
   * An inventory past [MAX_CHECKPOINT_OWNED_PATHS] is rejected as a typed projection failure rather
   * than left to trip the briefing framing ceiling: that ceiling throws `IllegalArgumentException`,
   * which the launch path does not catch, so it would unwind past the handler that already persisted
   * STATUS_RUNNING and leave the row running with no blocked reason. Truncating instead is not an
   * option — audit would read a silently narrowed scope as the complete one.
   */
  private fun checkpointOwnedPaths(run: PhaseRun, baselineOwnedPaths: List<String>): List<String>? {
    val owned = gitOperations.repositoryOwnedPaths(run.request.repoRoot)
    if (!owned.ok) return null
    val baseline = baselineOwnedPaths.toSet()
    val paths = owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in baseline }
      .filterNot { path -> isFeatureSpecPathForIssue(path, run.request.issueKey) }
      .distinct()
      .sorted()
    if (paths.size > MAX_CHECKPOINT_OWNED_PATHS) {
      val declaration = run.declaration.projectionDeclarations.first { projection ->
        projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
      }
      throw InvalidFeatureTaskRuntimeHandoffProjectionError(
        workflowId = run.request.workflowId,
        consumerPhaseId = run.phaseId,
        projectionName = declaration.projectionName,
        projectionContractId = declaration.projectionContractId,
        projectionContractVersion = declaration.projectionContractVersion,
        failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
        reason = "the scoped owned-path inventory holds ${paths.size} entries, over the " +
          "$MAX_CHECKPOINT_OWNED_PATHS-entry checkpoint limit; narrow the run scope or commit " +
          "unrelated working-tree changes before relaunching",
      )
    }
    return paths
  }

  private fun completedPhaseRepositoryFingerprint(run: PhaseRun) = if (
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ||
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
  ) {
    gitOperations.repositoryFingerprint(run.request.repoRoot)
  } else {
    null
  }

  @Suppress("ReturnCount")
  private fun auditRepairNonProgressReason(
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    repositoryFingerprint: String?,
  ): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    // The criteria the previous audit reported are exactly what this remediation round was given, so the
    // re-entry carries them; the current output carries what this audit decided. No durable repair
    // ledger is involved. An absent fingerprint means repository change could not be proven, which is
    // not evidence that anything moved: treat it as unchanged so an unchanged criterion set blocks
    // rather than disarming the only bound on the audit-gap cycle.
    val currentCriterionRefs = FeatureTaskRuntimeOutputVerification
      .unmetAuditCriteria(outputMap)
      .mapTo(linkedSetOf()) { it.uppercase() }
    val previousCriterionRefs = previousAuditCriterionRefs
    val previousFingerprint = previousAuditFingerprint
    previousAuditCriterionRefs = currentCriterionRefs
    previousAuditFingerprint = repositoryFingerprint
    if (previousCriterionRefs.isEmpty() || currentCriterionRefs.isEmpty()) return null
    return detectAuditRepairNonProgress(
      previousCriterionRefs = previousCriterionRefs,
      currentCriterionRefs = currentCriterionRefs,
      previousRepositoryFingerprint = previousFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
      currentRepositoryFingerprint = repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
    ).reason
  }

  private fun terminalOutputAttempt(
    run: PhaseRun,
    iteration: Int,
    reason: String,
    outputMap: Map<String, Any?>,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult {
    val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, outputMap)
    return if (
      disposition.retryOnResume &&
      FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)
    ) {
      // A retryable blocked/failed envelope re-enters the semantic fix loop as itself. It is NOT
      // relabelled schema-invalid: it validated, and converting it would both misreport the run and
      // charge the structural-repair budget for a document with nothing structurally wrong.
      AttemptResult.retryableTerminal(reason, fileManifest, disposition)
    } else {
      AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = disposition,
          fileManifest = fileManifest,
          normalizedOutput = normalizedOutput,
          repairEvidence = repairEvidence,
        ),
      )
    }
  }

  private fun outputVerificationGateReason(phaseId: String, outputMap: Map<String, Any?>): String? =
    reviewVerificationSignalGateReason(phaseId, outputMap)
      ?: auditVerificationSignalGateReason(phaseId, outputMap)

  /**
   * Rebuilds payload-free structural-repair evidence from digest/location fields carried on the
   * schema exception. Returns null when the throw had no correlated prior syntax repair.
   */
  private fun structuralRepairEvidenceFromSchemaError(
    error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  ): skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence? {
    val originalDigest = error.structuralRepairOriginalDigest
    val repairedDigest = error.structuralRepairRepairedDigest
    val format = error.structuralRepairFormat
    val operation = error.structuralRepairOperation
    val sourceLabel = error.structuralRepairSourceLabel
    val sourceOffset = error.structuralRepairSourceOffset
    val sourceLine = error.structuralRepairSourceLine
    val sourceColumn = error.structuralRepairSourceColumn
    if (
      listOf(
        originalDigest,
        repairedDigest,
        format,
        operation,
        sourceLabel,
        sourceOffset,
        sourceLine,
        sourceColumn,
      ).any { it == null }
    ) {
      return null
    }
    return skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat.fromWire(
        requireNotNull(format),
      ),
      originalDigest = requireNotNull(originalDigest),
      repairedDigest = requireNotNull(repairedDigest),
      operation = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation.fromWire(
        requireNotNull(operation),
      ),
      sourceLocation = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation(
        sourceLabel = requireNotNull(sourceLabel),
        offset = requireNotNull(sourceOffset),
        line = requireNotNull(sourceLine),
        column = requireNotNull(sourceColumn),
      ),
    )
  }

  private fun persistAcceptedOutput(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    repositoryFingerprint: String?,
  ): AttemptResult {
    val outputText = normalizedOutput.canonicalJson
    // Gate-repair segments stay RUNNING until the coordinator settles with runtime-measured
    // gate_runs; persisting the agent's validate receipt here would publish agent-authored counts.
    if (run.validationGateFindings != null) {
      return AttemptResult.settled(
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
    if (isGoalReviewRun(run)) {
      persistGoalReviewCompletion(
        run,
        iteration,
        normalizedOutput,
        repairEvidence,
        observability,
        fileManifest,
      )?.let { outcome ->
        return AttemptResult.settled(outcome)
      }
    } else {
      val persisted = recorder.recordCompletedPhase(
        phaseStateRequest(
          run,
          iteration,
          STATUS_COMPLETED,
          finished = true,
          outputArtifact = outputText,
          fileManifest = fileManifest,
          normalizedOutput = normalizedOutput,
          repairEvidence = repairEvidence,
          repositoryFingerprint = repositoryFingerprint,
        ),
        run.request.dbPathOverride,
      )
      if (!persisted) {
        return AttemptResult.settled(
          blockAndPersistInPhase(
            run,
            iteration,
            "Validated phase output could not be persisted to the authoritative workflow record.",
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
            fileManifest = fileManifest,
          ),
        )
      }
    }
    observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return AttemptResult.settled(
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

  private fun persistGoalReviewCompletion(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
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

  private fun isGoalReviewRun(run: PhaseRun): Boolean =
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)

  // A goal-subtask review reserves its pass once in prepareGoalReviewRun, outside runPhaseAttempts, so a
  // bounded in-loop re-attempt reuses that same reserved pass instead of allocating another. Schema-invalid
  // output therefore earns the same fix-loop retries as every other phase: the reserved pass has no completed
  // output, which is the state a resume is already contracted to re-enter rather than treat as terminal.
  private fun schemaInvalidAttempt(
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

  private fun persistPhase(
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

  private fun phaseStateRequest(
    run: PhaseRun,
    iteration: Int,
    status: String,
    finished: Boolean,
    outputArtifact: String?,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
    repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
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
  private data class LaunchedModelDirective(
    val modelOverride: String?,
    val effortOverride: String?,
    val persistedEffort: String?,
  )

  // Pure in [run]: both the durable running write and the launch request call it, and a shared value
  // threaded between them would only hide that they cannot disagree.
  private fun launchedModelDirective(run: PhaseRun): LaunchedModelDirective {
    val model = run.modelDirective?.model
    val effort = run.modelDirective?.effort
    if (run.resolvedAgent.resolvedAgentId == InstallAgent.CURSOR.id && model != null && effort != null) {
      return LaunchedModelDirective("$model[effort=$effort]", effort, persistedEffort = null)
    }
    return LaunchedModelDirective(model, effort, effort)
  }

  private fun reviewPassNumber(run: PhaseRun, state: FeatureTaskRuntimeRunState): Int? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    // In-memory outputs hold at most one record per phase, so their count collapses to <= 1 after a
    // resume while the durable pass watermark keeps climbing. The durable state is the counter.
    val durable = goalReviewStateOrNull()
    return resolveReviewPassNumber(
      reservedPassNumber = durable?.reservedPassNumber ?: state.currentReviewPassNumber(),
      completedReviewPassCount = durable?.completedPassCount ?: state.outputCountFor(run.phaseId),
    )
  }

  private fun prepareLaunch(
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
      reentryGapCriteria = auditGapCriteriaFor(run, state),
      durablyClosedCriterionRefs = durablyClosedCriterionRefs,
      repairLedger = remediationRepairLedger(run.phaseId),
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
    depthResolution?.let { resolution -> persistResolvedReviewTier(run, resolution) }
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      issueKey = run.request.issueKey,
      briefing = briefing,
      suppressDecomposition = isGoalContinuationRun(run.request),
      parallelReviewAgent = run.request.parallelReviewAgent
        ?.takeIf { run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW },
      codeReviewMode = depthResolution?.resolvedTier ?: run.request.runInvariants.codeReviewMode,
      reviewPassNumber = passNumber,
      goalSubtaskReviewInput = run.goalReviewInput,
      baselineUntrackedPaths = resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
      resolvedReviewTier = depthResolution?.resolvedTier,
      reviewDecidingRule = depthResolution?.decidingRule,
      repairLedger = handoff.repairLedger,
      priorReviewContext = remediationPriorReviewContext(run.phaseId, passNumber),
      priorSchemaFailure = priorCorrection?.schemaGateReason,
      priorTerminalFailure = priorCorrection?.retryableTerminalReason,
      priorFindingCoverage = priorCorrection?.findingCoverageReason,
      correctiveRepairContext = priorCorrection?.correctiveRepairContext,
      operatorBlockRetry = operatorBlockRetry
        ?.takeIf { it.phaseId == run.phaseId && !operatorBlockRetryCompleted },
      implementationContinuation = implementationContinuationFor(run),
      validationGateFindings = run.validationGateFindings,
      agentRunValidateFallback = run.agentRunValidateFallback,
      packCollectAllCommand = packCollectAllCommand(run, state),
    )
    return PreparedLaunch(briefing, prompt)
  }

  @Suppress("ReturnCount")
  private fun launchAndCapture(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorCorrection: PriorAttemptCorrection? = null,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): LaunchResult {
    val before = gitOperations.worktreeStatus(run.request.repoRoot)
    if (!before.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its before-file manifest: ${before.error}",
        childNeverLaunched = true,
      )
    }
    val beforeCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!beforeCommit.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its before commit: ${beforeCommit.error}",
        childNeverLaunched = true,
      )
    }
    val prepared = when (val preparation = prepareLaunchForCapture(run, state, priorCorrection)) {
      is PreparedLaunchReady -> preparation.value
      is LaunchPreparationRejected -> return preparation.result
      else -> error("Unexpected launch preparation result.")
    }
    val briefing = prepared.briefing
    val isReviewPhase = run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW

    val launched = launchedModelDirective(run)

    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = run.resolvedAgent.invokedAgentId,
        configuredAgentOverrideId = run.resolvedAgent.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = run.request.issueKey,
          repoRoot = run.request.repoRoot,
          dbPathOverride = run.request.dbPathOverride,
          timeout = run.request.timeout,
          modelOverride = launched.modelOverride,
          effortOverride = launched.effortOverride,
          compaction = run.compaction,
          promptOverride = prepared.prompt,
          readOnlyPhase = isReviewPhase,
          progressIdleTimeout = READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes.takeIf { isReviewPhase },
        ),
      ),
    )
    if (outcome is AgentRunLaunchFacts && phaseTokenAccumulator != null) {
      val inputTokens = estimateTokens(briefing.briefingText)
      val outputTokens = estimateTokens(outcome.stdout)
      phaseTokenAccumulator[run.phaseId] = Pair(inputTokens, outputTokens)
    }
    val after = gitOperations.worktreeStatus(run.request.repoRoot)
    if (!after.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its after-file manifest: ${after.error}",
        childNeverLaunched = false,
      )
    }
    val afterCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!afterCommit.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its after commit: ${afterCommit.error}",
        childNeverLaunched = false,
      )
    }
    val committedPaths = gitOperations.runtimePhaseChangedPathsBetweenCommits(
      run.request.repoRoot,
      beforeCommit.value.orEmpty(),
      afterCommit.value.orEmpty(),
    )
    if (!committedPaths.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture committed file changes: ${committedPaths.error}",
        childNeverLaunched = false,
      )
    }
    val fileManifest = FeatureTaskRuntimePhaseFileManifest(
      before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.value),
      after = (
        FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value) +
          FeatureTaskRuntimePhaseSafetyPolicy.lineSeparatedPaths(committedPaths.value.orEmpty())
        ).distinct().sorted(),
    )
    capturePhaseContentIdentities(run.phaseId)
    return reconcileLaunch(run.phaseId, outcome, fileManifest)
  }

  /**
   * Records what the phase left on disk the instant it stopped running. Anything that differs from
   * this at checkpoint time was written by someone other than the phase, which is the only way to
   * detect a concurrent unstaged edit to a file this workflow owns.
   */
  private fun capturePhaseContentIdentities(phaseId: String) {
    val owned = gitOperations.repositoryOwnedPaths(request.repoRoot)
    if (!owned.ok) return
    val paths = owned.value.orEmpty().split(OWNED_PATH_DELIMITER).map(String::trim).filter(String::isNotBlank)
    val identities = gitOperations.pathContentIdentities(request.repoRoot, paths)
    if (!identities.ok) return
    phaseContentIdentities[phaseId] = parseContentIdentities(identities.value.orEmpty())
  }

  private fun parseContentIdentities(raw: String): Map<String, String> = raw
    .split(OWNED_PATH_DELIMITER)
    .filter(String::isNotBlank)
    .mapNotNull { record ->
      val identity = record.substringBefore('\t', missingDelimiterValue = "")
      val path = record.substringAfter('\t', missingDelimiterValue = "")
      if (identity.isBlank() || path.isBlank()) null else path to identity
    }
    .toMap()

  private fun prepareLaunchForCapture(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorCorrection: PriorAttemptCorrection?,
  ): LaunchPreparation {
    val measurementContext = when (val resolution = resolveLaunchMeasurementContext(run, state)) {
      is LaunchMeasurementContextReady -> resolution.value
      is LaunchPreparationRejected -> return resolution
      else -> error("Unexpected launch measurement result.")
    }
    val durablyClosedCriterionRefs = when (
      val resolution = resolveDurablyClosedCriterionRefs(run, state, measurementContext)
    ) {
      is ClosedCriterionRefsReady -> resolution.value
      is LaunchPreparationRejected -> return resolution
      else -> error("Unexpected closed-criterion result.")
    }
    return prepareDeclaredLaunch(
      run,
      state,
      priorCorrection,
      durablyClosedCriterionRefs,
      measurementContext,
    )
  }

  private fun resolveLaunchMeasurementContext(run: PhaseRun, state: FeatureTaskRuntimeRunState): LaunchPreparation {
    val producerIteration = run.declaration.projectionDeclarations
      .map { declaration ->
        val phaseId = declaration.producerIteration.phaseId
        state.outputFor(phaseId)?.let { FeatureTaskRuntimeProducerIteration(phaseId, it.iteration) }
          ?: declaration.producerIteration
      }
      .maxByOrNull(FeatureTaskRuntimeProducerIteration::iteration)
      ?: FeatureTaskRuntimeProducerIteration(run.phaseId, 1)
    return try {
      LaunchMeasurementContextReady(
        LaunchRejectionMeasurementContext(
          producerIteration = producerIteration,
          repositoryCheckpoint = resolveRepositoryCheckpoint(run),
        ),
      )
    } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
      recordLaunchSeamRejection(
        run,
        state,
        FeatureTaskRuntimeProjectionFailureClassification.BUDGET_OVERFLOW,
        error.projectionName,
        producerIteration,
        null,
      )
      LaunchPreparationRejected(
        LaunchResult.projectionRejected(
          "Feature-task-runtime phase '${run.phaseId}' could not resolve its repository checkpoint: ${error.message}",
        ),
      )
    }
  }

  private fun resolveDurablyClosedCriterionRefs(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparation = try {
    // Audit closure state is owned by audit itself, not an upstream producer. Its schema rejection
    // remains a durable block because regenerating a producer cannot repair it.
    ClosedCriterionRefsReady(
      if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
        durablyClosedCriterionRefs()
      } else {
        emptyList()
      },
    )
  } catch (error: InvalidWorkflowStateSchemaError) {
    recordLaunchSeamRejection(
      run,
      state,
      FeatureTaskRuntimeProjectionFailureClassification.UNSUPPORTED_VERSION,
      "durable_audit_state",
      context.producerIteration,
      context.repositoryCheckpoint,
    )
    LaunchPreparationRejected(
      LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' rejected its durable audit-repair state at the launch seam: " +
          error.message,
      ),
    )
  }

  private fun prepareDeclaredLaunch(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorCorrection: PriorAttemptCorrection?,
    durablyClosedCriterionRefs: List<String>,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparation = try {
    PreparedLaunchReady(
      prepareLaunch(
        run,
        state,
        priorCorrection,
        durablyClosedCriterionRefs,
        context.repositoryCheckpoint,
      ),
    )
  } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
    recordLaunchSeamRejection(
      run,
      state,
      error.failureKind.toMeasurementFailureClassification(),
      error.projectionName,
      context.producerIteration,
      context.repositoryCheckpoint,
    )
    LaunchPreparationRejected(
      LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' could not build its declared handoff projection: " +
          error.message,
      ),
    )
  } catch (error: InvalidFeatureTaskRuntimePhaseBriefingFramingError) {
    recordLaunchSeamRejection(
      run,
      state,
      FeatureTaskRuntimeProjectionFailureClassification.BUDGET_OVERFLOW,
      "phase_briefing",
      context.producerIteration,
      context.repositoryCheckpoint,
    )
    LaunchPreparationRejected(
      LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' could not fit its launch briefing under the byte ceiling: " +
          error.message,
      ),
    )
  } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
    recordLaunchSeamRejection(
      run,
      state,
      FeatureTaskRuntimeProjectionFailureClassification.INVALID_CONTRACT,
      error.projectionName ?: "planning_projection",
      context.producerIteration,
      context.repositoryCheckpoint,
    )
    LaunchPreparationRejected(
      LaunchResult.recordRejected(
        QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION,
        error.message.orEmpty(),
      ),
    )
  } catch (error: InvalidWorkflowStateSchemaError) {
    recordLaunchSeamRejection(
      run,
      state,
      FeatureTaskRuntimeProjectionFailureClassification.UNSUPPORTED_VERSION,
      "durable_briefing",
      context.producerIteration,
      context.repositoryCheckpoint,
    )
    LaunchPreparationRejected(
      LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' rejected a durable handoff envelope at the launch seam: " +
          error.message,
      ),
    )
  }

  private fun recordLaunchSeamRejection(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    classification: FeatureTaskRuntimeProjectionFailureClassification,
    sourceLabel: String,
    fallbackProducerIteration: FeatureTaskRuntimeProducerIteration,
    repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ) {
    val attribution = resolveLaunchRejectionAttribution(
      declarations = run.declaration.projectionDeclarations,
      projectionName = sourceLabel,
      currentProducerIteration = { phaseId -> state.outputFor(phaseId)?.iteration },
      fallbackProducerIteration = fallbackProducerIteration,
    )
    recorder.recordProjectionRejection(
      FeatureTaskRuntimeProjectionRejection(
        workflowId = run.request.workflowId,
        consumerPhaseId = run.phaseId,
        projectionContractId = attribution.projectionContractId,
        producerIteration = attribution.producerIteration,
        repositoryCheckpointFingerprint = repositoryCheckpoint?.fingerprint,
        failureClassification = classification,
        sourceLabel = sourceLabel,
      ),
      run.request.dbPathOverride,
    )
  }

  private data class LaunchRejectionMeasurementContext(
    val producerIteration: FeatureTaskRuntimeProducerIteration,
    val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  )

  private sealed interface LaunchPreparation

  private data class PreparedLaunchReady(val value: PreparedLaunch) : LaunchPreparation

  private data class LaunchMeasurementContextReady(
    val value: LaunchRejectionMeasurementContext,
  ) : LaunchPreparation

  private data class ClosedCriterionRefsReady(val value: List<String>) : LaunchPreparation

  private data class LaunchPreparationRejected(val result: LaunchResult) : LaunchPreparation

  private fun auditGapCriteriaFor(run: PhaseRun, state: FeatureTaskRuntimeRunState): List<String> {
    run.reentry
      ?.takeIf { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID }
      ?.reentryGapCriteria
      ?.let { return it }
    val scopedPhases = setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
    )
    val auditGapFired =
      state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) > 0
    if (run.phaseId !in scopedPhases || !auditGapFired) {
      return emptyList()
    }
    return state.unmetAuditCriteria(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
  }

  private fun reconcileLaunch(
    phaseId: String,
    outcome: AgentRunLaunchOutcome,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): LaunchResult = when (outcome) {
    is UnsupportedAgentRunLaunch -> LaunchResult.infraFailure(
      "Feature-task-runtime phase '$phaseId' could not launch an agent: ${outcome.reason}",
      fileManifest,
      childNeverLaunched = true,
    )
    is AgentRunLaunchFacts -> providerLimitSignal(outcome)
      ?.let { LaunchResult.providerLimited(providerLimitPauseReason(phaseId, it), fileManifest) }
      ?: infraFailureReason(phaseId, outcome)
        // Only a failure before the process-start boundary proves no child ran; a timeout, an
        // interruption and a non-zero exit all happened after it, under the launched model. Both
        // flags are consulted because they are one fact reported two ways: the launcher adapter
        // rejects a disagreeing pair, and reading only one of them would trust the weaker signal.
        ?.let {
          LaunchResult.infraFailure(
            it,
            fileManifest,
            childNeverLaunched = outcome.spawnFailed || !outcome.processStarted,
          )
        }
      ?: LaunchResult.captured(
        outcome.stdout,
        outcome.stdoutBytes,
        outcome.stdoutTruncated,
        outcome.stdoutByteSize,
        outcome.stdoutSha256,
        fileManifest,
      )
  }

  private data class PhaseRun(
    val phaseId: String,
    val declaration: FeatureTaskRuntimePhaseDeclaration,
    val resolvedAgent: FeatureTaskRuntimeResolvedPhaseAgent,
    val modelDirective: PhaseModelDirective?,
    val compaction: PhaseCompactionDirective?,
    val request: FeatureTaskRuntimeRunRequest,
    val specSource: SpecSource,
    val reentry: PendingReentry? = null,
    val goalReviewInput: GoalSubtaskReviewInput? = null,
    val validationGateFindings: ValidationFindingSetProjection? = null,
    /** True only when validate falls back because the pack declares no validation_gate. */
    val agentRunValidateFallback: Boolean = false,
    /**
     * 1-based ordinal of the validation-gate repair turn this launch is, zero outside a repair cycle.
     * A repair cycle deliberately re-runs an agent under one unchanged phase attempt, so this is the
     * only thing separating one turn's retained evidence and diagnostics from the next turn's.
     */
    val validationGateRepairTurn: Int = 0,
  )

  private data class PreLaunchBlock(
    val attemptCount: Int,
    val reason: String,
    val durableRecord: FeatureTaskRuntimePhaseRecord? = null,
  )

  private data class PreparedLaunch(
    val briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    val prompt: String,
  )

  // SKILL-140: a launch-seam rejection of an upstream producer's durable record, carrying only the
  // typed class and bounded validation detail; producer attribution happens in the run loop.
  private data class RecordRejection(val rejectionClass: String, val rejectionDetail: String)

  private data class RepairReceiptAnchor(val baseSha: String, val roundNumber: Int)

  /** Why a schema-valid repair receipt still owes work, which selects the budget that reads it. */
  private enum class FindingsOwedKind { OMITTED, UNRESOLVED }

  private sealed interface RepairReceiptSettlement {
    private data class Rejected(val detail: String) : RepairReceiptSettlement
    private data class WriteFailed(val reason: String) : RepairReceiptSettlement

    /**
     * The round left carried findings out of its receipt. Separate from [Rejected] because the
     * document is well-formed: the repair work is unfinished, so the round is sent back for exactly
     * these findings instead of spending the output-gate budget on a serialization it got right.
     */
    private data class Unaccounted(
      val omittedRefs: List<String>,
      val retryReason: String,
    ) : RepairReceiptSettlement

    /**
     * The round declared it tried and could not close a finding. Retryable once per finding, so it
     * carries the refs the budget counts as well as the producer's account of what still fails.
     */
    private data class Unresolved(
      val refs: Set<String>,
      val detail: String,
      val retryReason: String,
    ) : RepairReceiptSettlement

    data object None : RepairReceiptSettlement

    val rejectionDetail: String? get() = (this as? Rejected)?.detail
    val writeFailureReason: String? get() = (this as? WriteFailed)?.reason
    val unaccountedOmittedRefs: List<String>? get() = (this as? Unaccounted)?.omittedRefs
    val unaccountedRetryReason: String? get() = (this as? Unaccounted)?.retryReason
    val unresolvedRefs: Set<String>? get() = (this as? Unresolved)?.refs
    val unresolvedDetail: String? get() = (this as? Unresolved)?.detail
    val unresolvedRetryReason: String? get() = (this as? Unresolved)?.retryReason

    companion object {
      fun rejected(detail: String): RepairReceiptSettlement = Rejected(detail)
      fun writeFailed(reason: String): RepairReceiptSettlement = WriteFailed(reason)
      fun unaccounted(omittedRefs: List<String>, retryReason: String): RepairReceiptSettlement =
        Unaccounted(omittedRefs, retryReason)
      fun unresolved(refs: Set<String>, detail: String, retryReason: String): RepairReceiptSettlement =
        Unresolved(refs, detail, retryReason)
    }
  }

  private sealed interface LaunchResult {
    private data class Captured(
      val stdout: String,
      val stdoutBytes: ByteArray,
      val stdoutTruncated: Boolean,
      val stdoutByteSize: Long,
      val stdoutSha256: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ) : LaunchResult
    private data class InfraFailure(
      val reason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
      val disposition: FeatureTaskRuntimeFailureDisposition,
      val neverLaunched: Boolean,
    ) : LaunchResult
    private data class RecordRejected(
      val rejection: RecordRejection,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    ) : LaunchResult

    // Not an InfraFailure with a nicer string: this launch settles the phase as PAUSED rather than
    // BLOCKED, so it must not reach the block seam at all.
    private data class ProviderLimited(
      val reason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
    ) : LaunchResult

    val capturedStdout: String? get() = (this as? Captured)?.stdout
    val capturedStdoutBytes: ByteArray? get() = (this as? Captured)?.stdoutBytes
    val capturedStdoutTruncated: Boolean get() = (this as? Captured)?.stdoutTruncated == true
    val capturedStdoutByteSize: Long? get() = (this as? Captured)?.stdoutByteSize
    val capturedStdoutSha256: String? get() = (this as? Captured)?.stdoutSha256
    val infraFailureReason: String? get() = (this as? InfraFailure)?.reason
    val providerLimitReason: String? get() = (this as? ProviderLimited)?.reason
    val recordRejection: RecordRejection? get() = (this as? RecordRejected)?.rejection

    /**
     * True only where the launch provably never produced a running child: a spawn failure, or a
     * pre-launch capture or declaration rejection. A timeout, an interruption, a non-zero exit and
     * a post-run capture failure all happened around a child that did run under the launched model,
     * so their records must keep it.
     *
     * [RecordRejected] never reaches here: its seam fires before any spawn, so
     * [settleRecordRejection] states never-launched at its own block seams rather than routing the
     * same fact through this getter.
     */
    val childNeverLaunched: Boolean
      get() = (this as? InfraFailure)?.neverLaunched == true
    val failureDisposition: FeatureTaskRuntimeFailureDisposition
      get() = (this as? InfraFailure)?.disposition ?: FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE
    val fileManifest: FeatureTaskRuntimePhaseFileManifest?

    companion object {
      fun captured(
        stdout: String,
        stdoutBytes: ByteArray,
        stdoutTruncated: Boolean,
        stdoutByteSize: Long,
        stdoutSha256: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
      ): LaunchResult = Captured(
        stdout,
        stdoutBytes,
        stdoutTruncated,
        stdoutByteSize,
        stdoutSha256,
        fileManifest,
      )
      fun infraFailure(
        reason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
        childNeverLaunched: Boolean,
      ): LaunchResult =
        InfraFailure(reason, fileManifest, FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE, childNeverLaunched)

      /** The provider refused at a usage limit: resumable on its own clock, so the phase pauses. */
      fun providerLimited(reason: String, fileManifest: FeatureTaskRuntimePhaseFileManifest? = null): LaunchResult =
        ProviderLimited(reason, fileManifest)

      /** Static declaration or configuration drift: retrying without operator action reproduces it. */
      fun projectionRejected(reason: String): LaunchResult =
        InfraFailure(reason, null, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, neverLaunched = true)

      /**
       * A durable upstream producer record was rejected at projection validation: the run quarantines
       * it and re-enters the producer under a bounded regeneration cap rather than blocking on first
       * occurrence.
       */
      fun recordRejected(rejectionClass: String, rejectionDetail: String): LaunchResult =
        RecordRejected(RecordRejection(rejectionClass, rejectionDetail))
    }
  }

  private sealed interface AttemptResult {
    private data class Settled(val outcome: PhaseOutcome) : AttemptResult

    /**
     * The two consumers of a schema-invalid attempt want different text and must not share one string.
     * [operatorReason] is the payload-free sentence a blocked row, telemetry event or status surface may
     * carry; [retryReason] is the constraint text the next fix-loop prompt needs to repair the output.
     *
     * Bounding belongs to whoever composes [retryReason] — [retryRejectionReason] for validator-authored
     * text — not to this constructor. Re-bounding a composed string here would spend the validator's
     * character budget on the runtime's own fixed preamble and truncate exactly the constraint the prompt
     * exists to deliver.
     */
    private data class SchemaInvalid(
      val operatorReason: String,
      val retryReason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
      override val rejectedOutput: String?,
      override val malformedOutput: Boolean,
      override val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
    ) : AttemptResult

    /**
     * A schema-VALID receipt that did not close every obligation the plan declared. It gets its own
     * variant rather than reusing [SchemaInvalid] because nothing about it is invalid: recording it as
     * a rejected output would file honest partial work as malformed serialization, and routing it
     * through the schema path would spend the structural-repair budget on a structurally fine
     * document. [malformedOutput] and [schemaInvalidRetryReason] stay false/null for it, which is what
     * keeps it out of the format-correction cap.
     */
    private data class IncompleteWork(
      val operatorReason: String,
      val continuationReason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
      val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    ) : AttemptResult

    /**
     * A retryable `blocked` or `failed` envelope. It is a schema-valid terminal signal that happens to
     * be retryable, so it re-enters the semantic fix loop WITHOUT being relabelled schema-invalid and
     * without consuming the malformed-output or structural-repair budget.
     */
    private data class RetryableTerminal(
      val operatorReason: String,
      val retryReason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
      val failureDisposition: FeatureTaskRuntimeFailureDisposition,
    ) : AttemptResult

    /**
     * A well-formed repair receipt that still owes work on named carried findings — either it left
     * them out, or it reported it tried and they are still open.
     *
     * Its own variant for the same reason [IncompleteWork] is: nothing about the document is invalid,
     * so it must not spend the output-gate budget. It is not [IncompleteWork] either, because that
     * path rebuilds a plan-task continuation projection from durable implementation attempts, and
     * what this round owes is a named set of findings, not an unclosed obligation. The finding refs
     * are the budget, and [kind] selects which rule reads them.
     */
    private data class FindingsOwed(
      val kind: FindingsOwedKind,
      val operatorReason: String,
      val retryReason: String,
      val refs: Set<String>,
      val detail: String?,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ) : AttemptResult

    val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
    val schemaInvalidOperatorReason: String? get() = (this as? SchemaInvalid)?.operatorReason
    val schemaInvalidRetryReason: String? get() = (this as? SchemaInvalid)?.retryReason
    val fileManifest: FeatureTaskRuntimePhaseFileManifest?
      get() = when (this) {
        is SchemaInvalid -> fileManifest
        is IncompleteWork -> fileManifest
        is RetryableTerminal -> fileManifest
        is FindingsOwed -> fileManifest
        else -> null
      }
    val rejectedOutput: String? get() = (this as? SchemaInvalid)?.rejectedOutput
    val malformedOutput: Boolean get() = (this as? SchemaInvalid)?.malformedOutput == true
    val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?
      get() = (this as? SchemaInvalid)?.correctiveRepairContext

    /** The operator-facing sentence for whichever non-settled variant this is. */
    val retryableOperatorReason: String?
      get() = when (this) {
        is SchemaInvalid -> operatorReason
        is IncompleteWork -> operatorReason
        is RetryableTerminal -> operatorReason
        is FindingsOwed -> operatorReason
        else -> null
      }

    /**
     * Prompt-facing constraint text for the SCHEMA-correction fix loop; null on every other path.
     *
     * [RetryableTerminal] is deliberately absent: it is schema-valid, so feeding its reason here would
     * render it through the schema-correction directive and tell the agent its output was rejected
     * when it was not. It exposes its own [retryableTerminalRetryReason] instead.
     */
    val semanticRetryReason: String?
      get() = when (this) {
        is SchemaInvalid -> retryReason
        else -> null
      }

    /** Prompt-facing constraint text for a retryable terminal envelope's own continuation directive. */
    val retryableTerminalRetryReason: String? get() = (this as? RetryableTerminal)?.retryReason

    /** The envelope's declared disposition, so a capped terminal retry never blocks as INVALID_OUTPUT. */
    val retryableTerminalDisposition: FeatureTaskRuntimeFailureDisposition?
      get() = (this as? RetryableTerminal)?.failureDisposition

    /** Why this round still owes findings, or null when it owes none. */
    val findingsOwedKind: FindingsOwedKind? get() = (this as? FindingsOwed)?.kind

    /** The finding references the owed-work budget counts. */
    val findingsOwedRefs: Set<String>? get() = (this as? FindingsOwed)?.refs

    /** Prompt-facing continuation text naming what is still owed. */
    val findingsOwedRetryReason: String? get() = (this as? FindingsOwed)?.retryReason

    /** The producer's own account of what still fails, carried only by an unresolved report. */
    val findingsOwedDetail: String? get() = (this as? FindingsOwed)?.detail

    val incompleteWorkContinuationReason: String? get() = (this as? IncompleteWork)?.continuationReason
    val incompleteWorkOutput: NormalizedFeatureTaskRuntimePhaseOutput?
      get() = (this as? IncompleteWork)?.normalizedOutput

    companion object {
      fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)

      fun incompleteWork(
        operatorReason: String,
        continuationReason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
        normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
      ): AttemptResult = IncompleteWork(operatorReason, continuationReason, fileManifest, normalizedOutput)

      fun unaccountedItems(
        phaseId: String,
        itemNoun: String,
        unaccountedRefs: List<String>,
        retryReason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
      ): AttemptResult = FindingsOwed(
        kind = FindingsOwedKind.OMITTED,
        operatorReason = "Phase '$phaseId' left carried $itemNoun unaccounted for in its output: " +
          unaccountedRefs.joinToString(", ") + ".",
        retryReason = retryReason,
        refs = unaccountedRefs.toSet(),
        detail = null,
        fileManifest = fileManifest,
      )

      fun unresolvedFindings(
        unresolvedRefs: Set<String>,
        detail: String,
        retryReason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
      ): AttemptResult = FindingsOwed(
        kind = FindingsOwedKind.UNRESOLVED,
        operatorReason = "Phase 'implement_fix' reported carried review findings still open after " +
          "its attempt: ${unresolvedRefs.joinToString(", ")}.",
        retryReason = retryReason,
        refs = unresolvedRefs,
        detail = detail,
        fileManifest = fileManifest,
      )

      fun retryableTerminal(
        operatorReason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
        failureDisposition: FeatureTaskRuntimeFailureDisposition,
      ): AttemptResult = RetryableTerminal(operatorReason, operatorReason, fileManifest, failureDisposition)
      fun schemaInvalid(
        operatorReason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
        rejectedOutput: String?,
        malformedOutput: Boolean = false,
        retryReason: String = operatorReason,
        correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
      ): AttemptResult = SchemaInvalid(
        operatorReason = operatorReason,
        retryReason = retryReason,
        fileManifest = fileManifest,
        rejectedOutput = rejectedOutput,
        malformedOutput = malformedOutput,
        correctiveRepairContext = correctiveRepairContext,
      )
    }
  }

  private sealed interface PhaseOutcome {
    private data class Completed(val output: FeatureTaskRuntimePhaseOutput) : PhaseOutcome
    private data class Blocked(val reason: String) : PhaseOutcome

    // Non-terminal and resumable: the phase stopped for a condition that clears without operator
    // repair (today, a provider usage limit). Distinct from Blocked so no seam can settle the run as
    // terminally blocked on it.
    private data class Paused(val reason: String) : PhaseOutcome

    // SKILL-140: the consumer rejected an upstream producer's record and quarantined it; the run
    // settles the consumer with the RECORD_REJECTED verdict so the existing transition machinery
    // re-enters this producer under a bounded regeneration cap.
    private data class RegenerateProducer(val producerPhaseId: String) : PhaseOutcome

    val completedOutput: FeatureTaskRuntimePhaseOutput? get() = (this as? Completed)?.output

    val blockedReason: String? get() = (this as? Blocked)?.reason

    val pausedReason: String? get() = (this as? Paused)?.reason

    val regenerationTargetPhaseId: String? get() = (this as? RegenerateProducer)?.producerPhaseId

    companion object {
      fun completed(output: FeatureTaskRuntimePhaseOutput): PhaseOutcome = Completed(output)
      fun blocked(reason: String): PhaseOutcome = Blocked(reason)
      fun paused(reason: String): PhaseOutcome = Paused(reason)
      fun regenerateProducer(producerPhaseId: String): PhaseOutcome = RegenerateProducer(producerPhaseId)
    }
  }

  private sealed interface GoalReviewRunPreparation {
    data object CarryForward : GoalReviewRunPreparation
    class Blocked(
      val reason: String,
      val failureDisposition: FeatureTaskRuntimeFailureDisposition,
    ) : GoalReviewRunPreparation
  }

  private data class GoalReviewRunReady(val run: PhaseRun) : GoalReviewRunPreparation
}

// The review phase produces no durable workflow rows and no file activity by construction. Its inner
// phase launch therefore gets a longer idle-timeout budget so the supervisor does not kill a healthy
// read-only run whose only liveness signal is a live heartbeat.
private const val READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES = 30L

// Stands in for a repository fingerprint that could not be computed. Comparing it against itself
// yields "unchanged", so an audit that cannot prove the repository moved cannot claim progress.
private const val UNPROVEN_REPOSITORY_FINGERPRINT = "<unproven>"

// Bounds the goal-facing pause-reason label list so the reason stays a summary, not a transcript.
private const val MAX_PAUSE_REASON_LABELS = 5

// The block reason a pre-quarantine build persisted when a launch seam rejected an upstream bounded
// planning projection. The current seam quarantines the record and regenerates its producer instead,
// so this phrase is emitted by no live path and only ever matches a legacy durable row.
private const val LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION =
  "rejected an upstream bounded planning projection at the launch seam"

private const val EMPTY_AUDIT_GAP_CRITERIA_BLOCK_REASON =
  "Audit-gap recovery requires the unmet acceptance criteria the audit reported; the resumed " +
    "audit record carries none."

// NUL delimiter of the `-z` plumbing listing the checkpoint owned-path inventory is derived from.
private const val OWNED_PATH_DELIMITER = '\u0000'

// Bounds the rendered checkpoint scope well under the briefing framing ceiling, so an oversized
// inventory is rejected as a typed projection failure instead of tripping that ceiling's untyped throw.
private const val MAX_CHECKPOINT_OWNED_PATHS = 500

/**
 * Quotes a response wire verdict that must not reach retry prompts outside the repair section.
 *
 * The gate reason always continues with ` and no` after the closing quote. Locate that
 * gate-authored boundary from the end so an interior apostrophe — including one followed by
 * ` and no` inside the wire verdict (e.g. `x' and no y`) — cannot terminate the scrub early and
 * leave a response-derived suffix in Violated constraint. Index scan, not a lazy regex: repeated
 * unmatched prefixes must not amplify CPU across retries.
 */
private const val OFF_VOCABULARY_VERDICT_OPEN = "off-vocabulary verdict '"
private const val OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY = "' and no"

private fun scrubOffVocabularyVerdictQuote(text: String): String {
  val start = text.indexOf(OFF_VOCABULARY_VERDICT_OPEN, ignoreCase = true)
  if (start < 0) return text
  val afterOpenQuote = start + OFF_VOCABULARY_VERDICT_OPEN.length
  val closeAt = text.lastIndexOf(OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY)
  return if (closeAt >= afterOpenQuote) {
    text.substring(0, start) + "off-vocabulary verdict" + text.substring(closeAt + 1)
  } else {
    // Cap or malformation left no gate boundary — strip the open marker and remainder so a partial
    // response-derived quote cannot remain outside the repair section.
    text.substring(0, start) + "off-vocabulary verdict"
  }
}

/** Dual-reason validators sometimes append the instance dump after an em-dash or colon. */
private val OFFENDING_VALUE_APPENDIX_PATTERN =
  Regex("""(?:\s*[—-]\s*)?offending value:.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Audit repair gates list expected=/actual= receipt identifiers derived from the rejected output. */
private val EXPECTED_ACTUAL_LIST_PATTERN =
  Regex("""\bexpected=\[[^\]]*]\s*actual=\[[^\]]*]\.?""", RegexOption.IGNORE_CASE)

/** Length caps stated by typed audit-repair reference rules (`allows at most N characters`). */
private val BOUNDED_REF_LENGTH_CAP_PATTERN =
  Regex("""(?:allows|must be) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)

private val SCHEMA_DETAIL_TYPE_WORDS = setOf(
  "array",
  "boolean",
  "integer",
  "null",
  "number",
  "object",
  "string",
)

private const val MIN_RESPONSE_STRING_VALUE_LENGTH = 4

// The phases permitted to bring new paths into the workflow's durable ownership. Every other phase
// is a reader: a file appearing under one is outside its authority and blocks instead of being
// adopted, which is what keeps the outside-inventory policy reachable from production.
private val INVENTORY_EXTENDING_PHASES: Set<String> = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
)

private val REMEDIATION_LEDGER_CONSUMER_PHASE_IDS: Set<String> = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
)
