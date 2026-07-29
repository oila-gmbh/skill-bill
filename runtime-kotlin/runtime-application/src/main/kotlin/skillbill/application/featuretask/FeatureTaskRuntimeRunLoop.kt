package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
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
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.workflow.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.buildScopedGoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.WorkflowScopedCheckpointRequest
import skillbill.ports.workflow.repositoryCheckpointFingerprint
import skillbill.ports.workflow.repositoryFingerprint
import skillbill.ports.workflow.repositoryOwnedPaths
import skillbill.ports.workflow.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.runtimePhaseHeadCommit
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairGapIdentities
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_MAX_PASSES
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskPauseRelease
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.ImplementationCompleted
import skillbill.workflow.taskruntime.model.ImplementationCompletionDecision
import skillbill.workflow.taskruntime.model.ImplementationIncomplete
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.SchemaInvalidCorrection
import skillbill.workflow.taskruntime.model.SemanticIncompleteWorkContinuation
import skillbill.workflow.taskruntime.model.TerminalBlocked
import skillbill.workflow.taskruntime.model.UnresolvedConvergence
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope
import skillbill.workflow.taskruntime.model.implementationCompletionDecisionFromContext
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

internal fun reconcileCheckpointPathInventory(
  repoRoot: Path,
  specReference: String,
  specSource: SpecSource,
  paths: List<String>,
): List<String> {
  val specPath = Path.of(specReference)
    .let { path -> if (path.isAbsolute) repoRoot.relativize(path) else path }
    .normalize()
    .toString()
    .replace('\\', '/')
  val repositoryPaths = paths
    .map { it.replace('\\', '/').removePrefix("./") }
  return when (specSource) {
    SpecSource.LOCAL -> (repositoryPaths + specPath).distinct()
    SpecSource.LINEAR -> repositoryPaths
  }
}

@Suppress("LargeClass", "LongMethod", "LongParameterList", "TooManyFunctions")
internal class FeatureTaskRuntimeRunLoop(
  private val dependencies: FeatureTaskRuntimeRunLoopDependencies,
  context: FeatureTaskRuntimeRunLoopContext,
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

  private var resolvedBranch: String? = null
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
    val auditRepairState = if (auditGapLoop) {
      recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
    } else {
      null
    }
    // A blocked audit attempt replaces the phase record, erasing its copy of the accepted plan and the
    // unmet criteria. The durable repair state is the single authority and still holds both, and
    // drive() requires the phase-record copy to equal the durable accepted plan anyway, so recover from
    // the authority rather than blocking a resume that already has everything it needs.
    val auditRepairPlan = if (auditGapLoop) {
      state.auditRepairPlan(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
        ?: auditRepairState?.acceptedPlans?.lastOrNull()
    } else {
      null
    }
    return PendingReentry(
      phaseId = reentry.destinationPhaseId,
      loopId = loopId,
      edgeIteration = reentry.edgeIteration,
      drivingVerdict = reentry.drivingVerdict,
      reentryGapCriteria = if (auditGapLoop) {
        state.unmetAuditCriteria(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
          .ifEmpty { auditRepairPlan?.gaps.orEmpty().map { it.acceptanceCriterionRef } }
      } else {
        emptyList()
      },
      auditRepairPlan = auditRepairPlan,
      auditRepairState = auditRepairState,
      expectedRepositoryCheckpoint = if (
        loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      ) {
        reviewedCheckpointFingerprint()
      } else {
        null
      },
    )
  }

  private fun reviewedCheckpointFingerprint(): String? =
    recorder.loadDeliveredProjections(request.workflowId, request.dbPathOverride)
      ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.repositoryCheckpointFingerprint

  fun drive() {
    if (FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW in state.phasesRequiringDurableGateInvalidation()) {
      check(recorder.persistReviewGenerationInvalidation(request.workflowId, request.dbPathOverride)) {
        "Could not durably invalidate legacy review evidence for workflow '${request.workflowId}'."
      }
      state.resetInvalidatedReviewGeneration()
      if (pendingReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        pendingReentry = null
        activeReentry = null
      }
    }
    val resumedReentry = pendingReentry
    if (resumedReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      state.auditGapPlanningContextError()?.let { reason ->
        blockInvalidAuditGapRecovery(resumedReentry, reason)
        return
      }
      if (resumedReentry.auditRepairPlan == null || resumedReentry.auditRepairState == null) {
        blockInvalidAuditGapRecovery(
          resumedReentry,
          "Audit-gap recovery requires the exact durably persisted audit repair plan and state.",
        )
        return
      }
      if (resumedReentry.auditRepairPlan != resumedReentry.auditRepairState.acceptedPlans.lastOrNull()) {
        blockInvalidAuditGapRecovery(
          resumedReentry,
          "Audit-gap recovery requires the phase-record repair plan to be identical to the latest durable " +
            "accepted plan.",
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
    val reason = phaseRunReason(phaseId)
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
        blockAt(phaseId, reason)
        PhaseSettlement.stop()
      }
      else -> PhaseSettlement.completed(phaseId, state.verdictFor(phaseId))
    }
  }

  private fun phaseRunReason(phaseId: String): String? {
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE && !state.isComplete(phaseId)) {
      request.eventSink.emit(
        skillbill.application.model.FeatureTaskRuntimeRunEvent.FinalValidationStarted(
          workflowId = request.workflowId,
        ),
      )
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
    return reason
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
      runCatching { outputValidator.validateAndReadPhaseOutput(output, sourceLabel = phaseId) }.fold(
        onSuccess = { outputMap ->
          runCatching { completeReservedGoalReviewPass(output, outputMap) }.getOrElse { error ->
            "Completed goal-subtask review could not reconcile its reserved pass: ${error.message.orEmpty()}"
          }
        },
        onFailure = { error ->
          "Completed goal-subtask review output cannot reconcile its reserved pass: ${error.message.orEmpty()}"
        },
      )
    }
    ?: "Completed goal-subtask review has no durable output to reconcile its reserved pass."

  private fun completeReservedGoalReviewPass(output: String, outputMap: Map<String, Any?>): String? {
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap)
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
          repositoryCheckpoint = reviewedCheckpointFingerprint(),
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
        ?.takeIf {
          it.reviewCapReached || it.pausedForOperatorDecision || it.reviewSkippedByUser || it.completedPassCount >= 2
        }
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
    val normalizedOutput = outputValidator.normalizePhaseOutput(
      rawResult,
      sourceLabel = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    )
    recordCarriedForwardGoalReview(rawResult, normalizedOutput, reentry)
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
    rawResult: String,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    reentry: PendingReentry?,
  ) {
    val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    if (state.isComplete(phaseId)) {
      return
    }
    val iteration = state.nextIteration(phaseId)
    val priorRecord = state.recordFor(phaseId)
    recorder.recordCompletedPhase(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_COMPLETED,
        attemptCount = iteration,
        resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
        finished = true,
        outputArtifact = rawResult,
        loopId = reentry?.loopId,
        edgeIteration = reentry?.edgeIteration,
      ),
      request.dbPathOverride,
    )
    if (reentry != null) pendingReentry = null
    state.recordCompleted(FeatureTaskRuntimePhaseOutput(phaseId, iteration, rawResult, normalizedOutput))
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

  @Suppress("CyclomaticComplexMethod")
  private fun nextPhaseAfter(phaseId: String, verdict: FeatureTaskRuntimeVerdict): String? {
    operatorPauseRelease(phaseId)?.let { return it.target }
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
      settleExhaustedReviewSequence(phaseId) -> null
      loopId == null && !establishForwardCheckpoint(phaseId, transition.phaseId) -> null
      loopId == null -> transition.phaseId
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

  /**
   * The review sequence has run its passes and still carries unresolved findings. A disposed pass
   * with a surviving unresolved Blocker pauses resumably; an undisposed one blocks on cap exhaustion.
   * A disposed pass whose Blockers all resolved or were superseded settles neither and takes the
   * forward transition. Returns true when this settled the transition.
   */
  private fun settleExhaustedReviewSequence(phaseId: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return false
    val sequenceExhausted = state.completedReviewPassNumber() == GOAL_SUBTASK_REVIEW_MAX_PASSES ||
      state.outputCountFor(phaseId) >= GOAL_SUBTASK_REVIEW_MAX_PASSES
    if (!sequenceExhausted || state.unresolvedReviewFindings(phaseId).isEmpty()) return false
    return when {
      // The pause is built by the domain so its loop id and iteration count cannot drift from the
      // declared edge behavior.
      unresolvedBlockerDispositionPresent() -> {
        pauseOnUnresolvedBlocker(phaseId, reviewFixTerminalPause())
        true
      }
      goalReviewStateOrNull()?.blockerDispositions.isNullOrEmpty() -> {
        val reviewLoopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
        blockOnCapExhaustion(
          phaseId,
          FeatureTaskRuntimeNextPhase.TerminalBlock(
            loopId = reviewLoopId,
            edgeIteration = state.edgeIterationCount(reviewLoopId).coerceAtLeast(1),
            unresolvedVerdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          ),
        )
        true
      }
      else -> false
    }
  }

  private fun authoritativeAuditRepairPlanMatches(auditPhaseId: String): Boolean {
    val normalizedPlan = state.auditRepairPlan(auditPhaseId) ?: return false
    val durableState = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride) ?: return false
    return durableState.acceptedPlans.lastOrNull() == normalizedPlan
  }

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
      commitMessage = ::auditReviewCheckpointMessage,
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
   */
  private fun establishRemediationCheckpoint(precedingPhaseId: String, loopId: String): Boolean {
    val established = checkpointEstablished(
      precedingPhaseId = precedingPhaseId,
      commitMessage = ::remediationCheckpointMessage,
      blockedReason = ::remediationCheckpointBlockedReason,
    )
    if (!established) return false
    // Only the review_fix edge reserves a remediation review pass, so only it has a pre-fix base to
    // record. The audit_gap edge re-enters implement without one and must not be gated on it.
    if (loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return true
    return recordRemediationBaseSha(precedingPhaseId)
  }

  private fun checkpointEstablished(
    precedingPhaseId: String,
    commitMessage: (String) -> String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val branch = resolvedBranch ?: return true
    if (FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return true
    }
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return true
    }
    val status = phaseGates.gitOperations.worktreeStatus(request.repoRoot)
    return when {
      !status.ok -> blockCheckpoint(precedingPhaseId, branch, status.error, blockedReason)
      status.value.isBlank() -> true
      else -> commitCheckpoint(precedingPhaseId, branch, commitMessage, blockedReason)
    }
  }

  /**
   * The checkpoint commit has just captured the pre-fix tree, so HEAD here IS the pre-fix tree. The
   * reserved remediation pass reviews diff(this sha -> post-fix HEAD), which is what materializes a
   * defect the remediation itself introduces instead of leaving it to be caught incidentally.
   */
  private fun recordRemediationBaseSha(precedingPhaseId: String): Boolean {
    if (!isGoalContinuationRun(request)) return true
    // Without durable review state there is no reserved remediation pass to bound, so there is no
    // base to record and nothing this gate can protect.
    if (goalReviewStateOrNull() == null) return true
    val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    if (!head.ok || head.value.isBlank()) {
      return blockRemediationBaseSha(precedingPhaseId, head.error.ifBlank { "HEAD resolved to an empty sha." })
    }
    return runCatching {
      goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
        state.copy(remediationBaseSha = head.value.trim())
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

  @Suppress("ReturnCount")
  private fun commitCheckpoint(
    precedingPhaseId: String,
    branch: String,
    commitMessage: (String) -> String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val currentRepositoryPaths = phaseGates.gitOperations.repositoryOwnedPaths(request.repoRoot)
    if (!currentRepositoryPaths.ok) {
      return blockCheckpoint(
        precedingPhaseId,
        branch,
        "the current repository delta could not be listed: ${currentRepositoryPaths.error}",
        blockedReason,
      )
    }
    val ownedPaths = currentRepositoryPaths.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .sorted()
    if (ownedPaths.isEmpty()) {
      return blockCheckpoint(
        precedingPhaseId,
        branch,
        "the durable workflow-owned path inventory is empty",
        blockedReason,
      )
    }
    val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
      ?: return blockCheckpoint(
        precedingPhaseId,
        branch,
        "the durable resolved branch artifact is missing",
        blockedReason,
      )
    val loop = activeReentry?.loopId ?: "initial"
    val requestedGeneration = activeReentry?.edgeIteration ?: 1
    val generation = resolved.checkpointIdentities
      .filter { it.phase == precedingPhaseId && it.loop == loop }
      .maxOfOrNull { it.generation }
      ?.let { maxOf(requestedGeneration, it + 1) }
      ?: requestedGeneration
    val scopedCheckpointOperations = phaseGates.gitOperations.scopedCheckpointOperations
    val commit = scopedCheckpointOperations.createScopedCheckpoint(
      request.repoRoot,
      WorkflowScopedCheckpointRequest(
        branch = branch,
        phase = precedingPhaseId,
        loop = loop,
        generation = generation,
        ownedPaths = ownedPaths,
        expectedContentIdentities = resolved.workflowOwnedPathContentIdentities,
        observedPaths = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)
          ?.get(precedingPhaseId)
          ?.fileManifestIntroduced
          .orEmpty(),
        governedSpecRoot = runCatching {
          request.repoRoot.toAbsolutePath().normalize()
            .relativize(Path.of(request.runInvariants.specReference).toAbsolutePath().normalize())
            .parent
            ?.toString()
            ?.replace('\\', '/')
        }.getOrNull(),
        commitMessage = commitMessage(branch) +
          " [authority=repository-wide phase=$precedingPhaseId loop=$loop generation=$generation]",
      ),
    )
    if (!commit.ok) return blockCheckpoint(precedingPhaseId, branch, commit.error, blockedReason)
    val identity = commit.identity
      ?: return blockCheckpoint(precedingPhaseId, branch, "scoped checkpoint returned no identity", blockedReason)
    val identityPersisted = runCatching {
      recorder.recordCheckpointIdentity(request.workflowId, identity, request.dbPathOverride)
    }.getOrDefault(false)
    return if (identityPersisted) {
      true
    } else {
      val restored = scopedCheckpointOperations.restoreScopedCheckpointParent(request.repoRoot, identity)
      blockCheckpoint(
        precedingPhaseId,
        branch,
        if (restored.ok) {
          "checkpoint identity could not be persisted; the branch was restored to ${identity.parentSha}"
        } else {
          "checkpoint identity could not be persisted and branch restoration failed: ${restored.error}"
        },
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

  private fun resumeInFlightReviewFix(edge: FeatureTaskRuntimeBackwardEdge): String? {
    if (edge.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return null
    val reviewRecord = state.recordFor(edge.fromPhaseId)
    if (reviewRecord?.reviewPassNumber == 2 || state.outputCountFor(edge.fromPhaseId) >= 2) return null
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
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
        state.auditRepairPlan(edge.fromPhaseId)
      } else {
        null
      },
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
        recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
      } else {
        null
      },
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        reviewedCheckpointFingerprint()
      } else {
        null
      },
    )
    activeReentry = pendingReentry
    observability.loopEdge(destinationPhaseId, loopId, edgeIteration, verdict)
  }

  private fun capExhaustedOnResume(phaseId: String): String? {
    val record = state.recordFor(phaseId) ?: return null
    return capExhaustionForRecord(phaseId, record)
  }

  private fun capExhaustionForRecord(phaseId: String, record: FeatureTaskRuntimePhaseRecord): String? {
    if (
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      record.reviewPassNumber == 2 &&
      record.blockedReason?.startsWith("Backward-edge loop '") != true
    ) {
      return null
    }
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
    return outcome.blockedReason ?: run {
      val completedOutput = requireNotNull(outcome.completedOutput)
      state.recordCompleted(completedOutput)
      if (operatorBlockRetry?.phaseId == phaseId) operatorBlockRetryCompleted = true
      applyPlanningStop(phaseId, completedOutput)
    }
  }

  private fun blockInvalidAuditGapRecovery(reentry: PendingReentry, reason: String) {
    val phaseId = reentry.phaseId
    val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = request.agentAssignment,
      invokedAgentId = request.invokedAgentId,
    ).resolvedAgentId
    val attempt = state.nextIteration(phaseId)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = attempt,
        resolvedAgentId = resolvedAgentId,
        finished = false,
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

  /**
   * An operator-granted `retry_fix` is unbudgeted: it discounts one consumed `review_fix` iteration so
   * the granted re-entry is not refused by a cap the operator has already overridden. `perEdgeCap`
   * itself is unchanged; only this one granted iteration is exempt from its accounting.
   */
  private fun effectiveEdgeIterationCount(edge: FeatureTaskRuntimeBackwardEdge): Int {
    val consumed = state.edgeIterationCount(edge.loopId)
    val granted = operatorRetryGrantActive() &&
      edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    return FeatureTaskRuntimeOperatorRetryGrant.discountedIterationCount(consumed, granted)
  }

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

  private fun reviewFixTerminalPause(): FeatureTaskRuntimeNextPhase.TerminalPause {
    val loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    val edge = transitions.backwardEdges.firstOrNull { it.loopId == loopId }
      ?: error("The review_fix backward edge must be declared to pause on an unresolved Blocker.")
    return FeatureTaskRuntimeTransitionFunction.terminalPauseFor(
      edge = edge,
      edgeIterationCount = state.edgeIterationCount(loopId).coerceAtLeast(1),
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
    )
  }

  private class PauseReleaseTarget(val target: String?)

  /**
   * Routes a recorded operator decision to the outcome it names. `retry_fix` is handled by the grant
   * seam and falls through to the normal backward-edge transition; `accept_and_advance` releases the
   * subtask forward to `validate` with its unresolved Blockers accepted; `abandon_subtask` ends it.
   * Every decision is consumed durably so the release happens exactly once.
   */
  private fun operatorPauseRelease(phaseId: String): PauseReleaseTarget? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    return when (goalReviewStateOrNull()?.pauseRelease) {
      null, GoalSubtaskPauseRelease.RETRY_FIX -> null
      GoalSubtaskPauseRelease.ADVANCE -> {
        operatorRetryGrantConsumed = true
        operatorGrantedFixIteration = false
        checkNotNull(
          goalContinuationRecorder.acceptUnresolvedReviewBlockers(
            request.workflowId,
            request.dbPathOverride,
          ),
        ) { "Could not durably accept carried Blockers before advancing." }
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

  /** Every generation keys dispositions to the durable cross-generation Blocker ledger. */
  private fun priorBlockerFindingIds(passNumber: Int? = null): List<String> {
    if (passNumber == null && !isGoalContinuationRun(request)) return emptyList()
    return recorder.unresolvedReviewBlockers(request.workflowId, request.dbPathOverride)
      .map { it.findingId }
  }

  /**
   * An unreadable review record loud-fails rather than reading as "no state": swallowing it here
   * would report no unresolved Blocker and walk the child straight past the pause gate. The sibling
   * read seams already fail loudly, so this one must agree with them.
   */
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
      unresolvedBlockerPresent = recorder
        .unresolvedReviewBlockers(request.workflowId, request.dbPathOverride)
        .isNotEmpty(),
    )

  /**
   * SKILL-141's non-terminal resumable status, not a block: the persisted review state, its
   * `review_base_sha`, the baseline untracked inventory, and the consumed pass count survive intact
   * so resume never re-reserves a consumed pass. The bounded operator decision over `retry_fix`,
   * `accept_and_advance`, and `abandon_subtask` is what releases it.
   */
  private fun pauseOnUnresolvedBlocker(phaseId: String, transition: FeatureTaskRuntimeNextPhase.TerminalPause) {
    val unresolvedCount = recorder
      .unresolvedReviewBlockers(request.workflowId, request.dbPathOverride)
      .size
    val reason = "Goal-subtask review pass ${transition.edgeIteration} left $unresolvedCount Blocker " +
      "disposition(s) unresolved after the single bounded fix attempt. The subtask is paused and " +
      "resumable; choose retry_fix, accept_and_advance, or abandon_subtask to continue."
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
        loopId = transition.loopId,
        edgeIteration = transition.edgeIteration,
      ),
      dbOverride = request.dbPathOverride,
    )
    pauseAt(phaseId, reason, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX)
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
    if (!reviewState.pausedForOperatorDecision) {
      return "The subtask is not paused; an operator decision is only accepted while it is paused."
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

  private fun remediationCheckpointMessage(branch: String): String =
    "chore(skill-bill): remediation checkpoint on '$branch' before mutating-phase re-entry"

  private fun auditReviewCheckpointMessage(branch: String): String =
    "chore(skill-bill): audited implementation checkpoint on '$branch' before review"

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
    val auditRepairPlan: FeatureTaskRuntimeAuditRepairPlan? = null,
    val auditRepairState: FeatureTaskRuntimeAuditRepairState? = null,
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
    val completedReviewBudgetOutput = completedReviewBudgetOutput(phaseId, state)
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
    settledFullyClosedAudit(run, state, observability)?.let { return it }
    completedReviewBudgetOutput
      ?.let { output -> settleCompletedReviewBudget(run, state, observability, output) }
      ?.let { return it }
    preLaunchBlock(run, state, observability)?.let { return it }
    return when (val prepared = prepareGoalReviewRun(run, observability)) {
      is GoalReviewRunReady -> {
        // Preflight reads the established review scope rather than rebuilding it, so the gate and
        // the review it guards can never disagree about which packs this delta routes to.
        if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
          phaseGates.reviewNativeAgentPreflight(request, prepared.run.goalReviewInput)
        }
        runPhaseAttempts(prepared.run, state, observability, phaseTokenAccumulator)
      }
      GoalReviewRunPreparation.CarryForward -> settleCarriedForwardGoalReview(
        run = run,
        state = state,
        observability = observability,
      )
      GoalReviewRunPreparation.Blocked -> PhaseOutcome.blocked(
        "Goal-subtask review preparation could not establish the exact durable review scope.",
      )
    }
  }

  private fun settleCompletedReviewBudget(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    output: FeatureTaskRuntimePhaseOutput,
  ): PhaseOutcome {
    if (!isGoalContinuationRun(run.request) || run.reentry == null) return PhaseOutcome.completed(output)
    val iteration = state.nextIteration(run.phaseId)
    val phaseState = phaseStateRequest(
      run,
      iteration,
      STATUS_COMPLETED,
      finished = true,
      outputArtifact = output.payload,
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(output.copy(phaseId = run.phaseId, iteration = iteration))
  }

  private fun declaredCriterionRefs(): List<String> =
    acceptanceCriterionRefsFor(request.runInvariants.acceptanceCriteria.size)

  private fun durablyClosedCriterionRefs(): List<String> {
    val closed = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
      ?.satisfiedCriterionRefs
      .orEmpty()
    val undeclared = closed.filterNot { it in declaredCriterionRefs().toSet() }.sorted()
    if (undeclared.isNotEmpty()) {
      throw InvalidWorkflowStateSchemaError(
        "audit_repair_state.satisfied_criterion_refs contains criteria not declared by this run: $undeclared.",
      )
    }
    return closed
  }

  private fun openAuditCriterionRefs(closedCriterionRefs: List<String> = durablyClosedCriterionRefs()): List<String> =
    declaredCriterionRefs() - closedCriterionRefs.toSet()

  /**
   * Settles the audit as satisfied without launching a child when every acceptance criterion is
   * already durably closed. The audit has nothing left to verify, so launching one could only produce
   * a gap against a closed criterion, which the closure gate rejects anyway.
   */
  private fun settledFullyClosedAudit(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val closedCriterionRefs = fullyClosedAuditCriterionRefs(run) ?: return null
    val iteration = state.nextIteration(run.phaseId)
    val outputText = fullyClosedAuditOutput(closedCriterionRefs)
    val normalizedOutput = runCatching {
      outputValidator.normalizePhaseOutput(outputText, sourceLabel = run.phaseId)
    }.getOrElse { error ->
      return blockAndPersistInPhase(
        run,
        iteration,
        "Audit settlement derived from durable criterion closure did not validate: ${error.message.orEmpty()}",
        observability,
      )
    }
    val persisted = recorder.recordCompletedPhase(
      phaseStateRequest(
        run,
        iteration,
        STATUS_COMPLETED,
        finished = true,
        outputArtifact = outputText,
        normalizedOutput = normalizedOutput,
      ),
      run.request.dbPathOverride,
    )
    if (!persisted) {
      return blockAndPersistInPhase(
        run,
        iteration,
        "Audit settlement derived from durable criterion closure could not be persisted.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText, normalizedOutput),
    )
  }

  private fun fullyClosedAuditCriterionRefs(run: PhaseRun): List<String>? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val repairState = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride) ?: return null
    val closedCriterionRefs = repairState.satisfiedCriterionRefs
    return closedCriterionRefs.takeIf {
      repairState.unresolvedGapLedger.unresolvedGaps.isEmpty() &&
        closedCriterionRefs.isNotEmpty() &&
        openAuditCriterionRefs(closedCriterionRefs).isEmpty()
    }
  }

  private fun fullyClosedAuditOutput(closedCriterionRefs: List<String>): String = JsonSupport.mapToJsonString(
    mapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "status" to STATUS_COMPLETED,
      "verdict" to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
      "summary" to "Every acceptance criterion reached a satisfied verdict in an earlier audit and is durably " +
        "closed, so this audit settles satisfied from that closure without re-verifying a closed criterion.",
      "produced_outputs" to mapOf(
        "unmet_criteria" to emptyList<Any?>(),
        "durably_closed_criteria" to closedCriterionRefs.sorted(),
      ),
    ),
  )

  private fun prepareGoalReviewRun(
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = when {
    run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> GoalReviewRunReady(run)
    isGoalReviewRun(run) -> reserveGoalReviewRun(run, observability)
    else -> prepareStandaloneReviewRun(run, observability)
  }

  @Suppress("ReturnCount")
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
    val checkpoint = resolved.checkpointIdentities.lastOrNull()
    val result = if (checkpoint == null) {
      phaseGates.gitOperations.buildGoalSubtaskReviewInput(
        run.request.repoRoot,
        GoalSubtaskReviewBaseline(reviewBaseSha, resolved.baselineUntrackedPaths),
        resolved.branch,
      )
    } else {
      val frozenPaths = checkpoint.ownedPaths.distinct().sorted()
      val frozenDigest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(frozenPaths.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }
      if (frozenDigest != checkpoint.ownedPathDigest) {
        return blockedGoalReviewRun(run, observability, "Checkpoint owned-path inventory digest mismatch.")
      }
      phaseGates.gitOperations.buildScopedGoalSubtaskReviewInput(
        run.request.repoRoot,
        GoalSubtaskReviewBaseline(checkpoint.parentSha, emptyList()),
        resolved.branch,
        checkpoint.commitSha,
        frozenPaths,
      )
    }
    val input = result.input
      ?: return blockedGoalReviewRun(run, observability, result.error.ifBlank { "Standalone review input failed." })
    return GoalReviewRunReady(run.copy(goalReviewInput = input))
  }

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
    goalContinuationRecorder.buildGoalReviewInput(
      workflowId = run.request.workflowId,
      gitOperations = phaseGates.gitOperations,
      repoRoot = run.request.repoRoot,
      dbOverride = run.request.dbPathOverride,
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
    return GoalReviewRunPreparation.Blocked
  }

  private fun settleCarriedForwardGoalReview(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val output = runCatching {
      goalContinuationRecorder.lastGoalReviewResult(run.request.workflowId, run.request.dbPathOverride)
    }.getOrElse { error ->
      return blockAndPersist(
        run,
        state.nextIteration(run.phaseId),
        "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: " +
          error.message.orEmpty(),
        observability,
      )
    }
      ?: return blockAndPersist(
        run,
        state.nextIteration(run.phaseId),
        "Goal-subtask review pass budget is exhausted but its durable raw review result is missing.",
        observability,
      )
    val normalizedOutput = runCatching {
      outputValidator.normalizePhaseOutput(output, sourceLabel = run.phaseId)
    }.getOrElse { error ->
      return blockAndPersist(
        run,
        state.nextIteration(run.phaseId),
        "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: " +
          error.message.orEmpty(),
        observability,
      )
    }
    val iteration = state.nextIteration(run.phaseId)
    val phaseState = phaseStateRequest(run, iteration, STATUS_COMPLETED, finished = true, outputArtifact = output)
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output, normalizedOutput),
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
        run.reentry.auditRepairPlan != null &&
        run.reentry.auditRepairState != null
    return missingUpstream(run.declaration, state.outputs())
      ?.filterNot {
        recoverableAuditRepairSource && it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
      }
      ?.takeIf(List<String>::isNotEmpty)
  }

  private fun isRetryableGoalReviewPreparation(phaseId: String, reason: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return false
    if (reason == "Goal-child review requires a persisted immutable checkpoint.") return true
    if (
      reason.startsWith("Goal-subtask review input persistence failed") &&
      reason.contains("does not match the durable review baseline")
    ) {
      return true
    }
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
  // rather than terminal: the reserved pass still has no completed output, which the bounded fix loop is
  // now what decides. The remaining attempt budget is deliberately not restarted.
  private fun isRemovedGoalReviewSchemaGateBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass")

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
  // fix-loop budget for the re-enterable stale-block classes whose prior attempts were not real output
  // attempts (goal-review preparation retries, launch-seam record rejections).
  private fun shouldRelaunchPersistedBlock(
    state: FeatureTaskRuntimeRunState,
    phaseId: String,
    durable: FeatureTaskRuntimePhaseRecord?,
    persistedReason: String,
  ): Boolean {
    val retryReviewPreparation = isRetryableGoalReviewPreparation(phaseId, persistedReason) ||
      state.legacyReviewPreparationRetryConsumedBudget(phaseId, persistedReason)
    val reenterableRecordRejection = isReenterableRecordRejection(state, phaseId, persistedReason)
    if (retryReviewPreparation || reenterableRecordRejection) {
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
      retryReviewPreparation -> true
      reenterableRecordRejection -> true
      isRemovedGoalReviewSchemaGateBlock(phaseId, persistedReason) -> true
      disposition != null -> disposition.retryOnResume
      else -> FeatureTaskRuntimeFixLoopPolicy.participatesInFixLoop(phaseId)
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
    val budgetBaseOffset = iteration - state.fixLoopIterationFor(run.phaseId, iteration)
    FeatureTaskRuntimeFixLoopPolicy
      .blockReasonIfBudgetExhausted(run.phaseId, iteration - budgetBaseOffset)
      ?.let { reason -> return blockAndPersistInPhase(run, iteration, reason, observability) }
    observability.started(
      run.phaseId,
      agentId,
      iteration,
      iteration > 1 || state.hasPriorRecord(run.phaseId),
      run.modelDirective,
    )
    var outcome: PhaseOutcome? = null
    var priorSchemaFailure: String? = null
    var malformedAttemptCount = 0
    var semanticIteration = iteration - budgetBaseOffset
    while (outcome == null) {
      val attempt = attemptOnce(run, state, iteration, observability, priorSchemaFailure, phaseTokenAccumulator)
      val semanticIncompleteReason = attempt.semanticIncompleteReason
      outcome = attempt.settledOutcome ?: if (semanticIncompleteReason != null) {
        when (val decision = FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(run.phaseId, semanticIteration)) {
          is FeatureTaskRuntimeFixLoopDecision.Retry -> {
            iteration += 1
            semanticIteration += 1
            priorSchemaFailure = semanticIncompleteReason
            observability.fixLoopIteration(run.phaseId, agentId, iteration, decision.fixLoopIteration)
            null
          }
          is FeatureTaskRuntimeFixLoopDecision.Block -> blockAndPersistInPhase(
            run,
            iteration,
            semanticIncompleteReason,
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.RETRYABLE,
            fileManifest = attempt.fileManifest,
          )
        }
      } else if (attempt.malformedOutput) {
        malformedAttemptCount += 1
        val formatBlock = FeatureTaskRuntimeFixLoopPolicy.malformedOutputBlockReason(
          run.phaseId,
          malformedAttemptCount,
        )
        if (formatBlock == null) {
          iteration += 1
          priorSchemaFailure = attempt.schemaRetryCorrectionReason
          observability.fixLoopIteration(run.phaseId, agentId, iteration, malformedAttemptCount)
          null
        } else {
          blockAndPersistInPhase(
            run,
            iteration,
            withSchemaGateDetail(formatBlock, requireNotNull(attempt.schemaInvalidReason)),
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          )
        }
      } else {
        when (val decision = FeatureTaskRuntimeFixLoopPolicy.decideAfterFailure(run.phaseId, semanticIteration)) {
          is FeatureTaskRuntimeFixLoopDecision.Retry -> {
            iteration += 1
            semanticIteration += 1
            priorSchemaFailure = attempt.schemaRetryCorrectionReason
            observability.fixLoopIteration(run.phaseId, agentId, iteration, decision.fixLoopIteration)
            null
          }
          is FeatureTaskRuntimeFixLoopDecision.Block -> blockAndPersistInPhase(
            run,
            iteration,
            withSchemaGateDetail(decision.blockedReason, requireNotNull(attempt.schemaInvalidReason)),
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          )
        }
      }
    }
    return outcome
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
    rejectedOutput: String? = null,
  ): PhaseOutcome {
    val phaseState = FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attemptCount.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      outputArtifact = outputArtifact,
      rejectedOutput = rejectedOutput,
      blockedReason = reason,
      failureDisposition = failureDisposition,
      fileManifestBefore = fileManifest?.before.orEmpty(),
      fileManifestAfter = fileManifest?.after.orEmpty(),
      fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
      loopId = loopId,
      edgeIteration = edgeIteration,
      reviewPassNumber = reviewPassNumber(run, state),
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    recorder.recordPhaseState(
      phaseState,
      run.request.dbPathOverride,
    )
    observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
    return PhaseOutcome.blocked(reason)
  }

  private fun blockAndPersistInPhase(
    run: PhaseRun,
    attemptCount: Int,
    reason: String,
    observability: FeatureTaskRuntimeRunObservability,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    outputArtifact: String? = null,
    rejectedOutput: String? = null,
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
    rejectedOutput = rejectedOutput,
  )

  /**
   * SKILL-140: a consumer's launch seam rejected an upstream producer's durable record. Quarantine the
   * rejected record as private evidence and settle the consumer with the RECORD_REJECTED verdict so the
   * existing transition machinery re-enters the producing phase under its bounded regeneration cap. A
   * record with no attributable producer, or whose producer the resolved pipeline dropped, blocks
   * durably with an actionable reason instead of attempting an impossible re-entry.
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
      return blockUnattributableRecordRejection(run, state, iteration, observability, rejection, producer)
    }
    val rejectedRecord = state.outputFor(producer)
    val producingIteration =
      (rejectedRecord?.iteration ?: state.recordFor(producer)?.attemptCount ?: 1).coerceAtLeast(1)
    val producerEvidence = recorder.producerOutput(
      request.workflowId,
      producer,
      producingIteration,
      request.dbPathOverride,
    ) ?: return blockAndPersistInPhase(
      run,
      iteration,
      "Feature-task-runtime phase '$consumer' rejected the durable record produced by '$producer', but exact " +
        "raw evidence for attempt $producingIteration is unavailable. The run blocks instead of fabricating " +
        "a rejected-output diagnostic from normalized workflow state.",
      observability,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )
    val rejectedPayload = producerEvidence.payload ?: byteArrayOf()
    val diagnosticIdentity = recordRejectedOutput(
      run = run,
      iteration = producingIteration,
      rule = "reconciliation-${rejection.rejectionClass}",
      reason = payloadFreeRejectionReason(
        "reconciliation-${rejection.rejectionClass}",
        rejectionPath(rejection.rejectionDetail),
      ),
      outputBytes = rejectedPayload,
      phaseId = producer,
      agentId = producerEvidence.agentId,
      model = producerEvidence.model,
      path = rejectionPath(rejection.rejectionDetail),
      outputByteSize = producerEvidence.byteSize,
      outputSha256 = producerEvidence.sha256,
      outputTruncated = producerEvidence.payload == null,
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
        diagnosticIdentity = diagnosticIdentity,
        rejectedRecordByteSize = producerEvidence.byteSize,
        rejectedRecordSha256 = producerEvidence.sha256,
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
      recorder.producerOutput(
        request.workflowId,
        output.phaseId,
        output.iteration.coerceAtLeast(1),
        request.dbPathOverride,
      )
    }
    evidence?.let {
      recordRejectedOutput(
        run = run,
        iteration = it.attempt,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason = detail,
        outputBytes = it.payload ?: byteArrayOf(),
        phaseId = it.phaseId,
        agentId = it.agentId,
        model = it.model,
        path = rejectionPath(rejection.rejectionDetail),
        outputByteSize = it.byteSize,
        outputSha256 = it.sha256,
        outputTruncated = it.payload == null,
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

  @Suppress("LongParameterList")
  private fun attemptOnce(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    priorSchemaFailure: String?,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): AttemptResult {
    persistPhase(run, iteration, STATUS_RUNNING, finished = false, outputArtifact = null)
    val launch = launchAndCapture(run, state, priorSchemaFailure, phaseTokenAccumulator)
    launch.infraFailureReason?.let { reason ->
      return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = launch.failureDisposition,
          fileManifest = launch.fileManifest,
        ),
      )
    }
    launch.recordRejection?.let { rejection ->
      return AttemptResult.settled(settleRecordRejection(run, state, iteration, observability, rejection))
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
    val normalized = outputValidator.normalizePhaseOutput(outputText, sourceLabel = run.phaseId)
    settleValidatedOutput(
      run, iteration, normalized, observability, fileManifest,
      outputBytes, outputTruncated, outputByteSize, outputSha256,
    )
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
    val path = rejectionPath(error.reason)
    val reason = payloadFreeRejectionReason("phase-output-schema", path)
    recordRejectedOutput(
      run, iteration, "phase-output-schema", reason, outputBytes, path = path,
      outputTruncated = outputTruncated, outputByteSize = outputByteSize, outputSha256 = outputSha256,
    )
    schemaInvalidAttempt(
      reason,
      fileManifest,
      malformedOutput = error.failureKind == FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED,
    )
  } catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
    val path = rejectionPath(error.reason)
    val reason = payloadFreeRejectionReason("audit-repair-plan-schema", path)
    recordRejectedOutput(
      run, iteration, "audit-repair-plan-schema", reason, outputBytes, path = path,
      outputTruncated = outputTruncated, outputByteSize = outputByteSize, outputSha256 = outputSha256,
    )
    schemaInvalidAttempt(reason, fileManifest)
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
  ): String {
    recorder.recordRejectedOutput(
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
      ),
      run.request.dbPathOverride,
    )
    return RejectedOutputDiagnosticService.stableIdentity(
      run.request.workflowId,
      phaseId,
      iteration.coerceAtLeast(1),
    )
  }

  @Suppress("CyclomaticComplexMethod", "ReturnCount")
  private fun settleValidatedOutput(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    outputBytes: ByteArray,
    outputTruncated: Boolean,
    outputByteSize: Long,
    outputSha256: String,
  ): AttemptResult {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
    fun reject(rule: String, detail: String): AttemptResult {
      val structuredIdentity = structuredRepairDiagnosticIdentity(detail)
      val diagnosticRule = structuredIdentity?.first ?: rule
      val path = structuredIdentity?.second ?: rejectionPath(detail)
      val reason = payloadFreeRejectionReason(rule, if (structuredIdentity == null) path else "/")
      recordRejectedOutput(
        run, iteration, diagnosticRule, reason, outputBytes, path = path,
        outputTruncated = outputTruncated, outputByteSize = outputByteSize, outputSha256 = outputSha256,
      )
      return schemaInvalidAttempt(
        publicReason = reason,
        retryCorrectionReason = retryCorrectionReason(detail, structuredIdentity, reason),
        fileManifest = fileManifest,
      )
    }
    firstValidatedOutputRejection(run.phaseId, outputText, outputMap)?.let { (rule, reason) ->
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
          outputArtifact = outputText,
        ),
      )
    }
    terminalBlockedReasonFrom(run.phaseId, outputMap)?.let { reason ->
      return terminalOutputAttempt(run, iteration, reason, outputText, outputMap, observability, fileManifest)
    }
    // Placed after the terminal path so a blocked or failed envelope never reaches it: only a phase
    // claiming 'completed' owes the projection its consumer will parse.
    producerProjectionGateReason(
      run.phaseId,
      outputMap,
      planningProjectionValidator,
      allowDecompositionPackage = true,
    )?.let { reason ->
      return reject("producer-projection", reason)
    }
    immediateConsumerProjectionGateReason(
      run,
      iteration,
      normalizedOutput,
      repositoryFingerprint,
    )?.let { reason ->
      return reject("consumer-projection", reason)
    }
    outputVerificationGateReason(run.phaseId, outputMap)?.let { reason ->
      return reject("output-verification", reason)
    }
    val checkpointManifest = prepareMutatingCheckpointCompletion(run, outputMap, fileManifest)?.let { preparation ->
      when (preparation) {
        is MutatingCheckpointCompletionBlocked -> {
          return AttemptResult.settled(
            blockAndPersistInPhase(
              run,
              iteration,
              preparation.reason,
              observability,
              failureDisposition = preparation.disposition,
              fileManifest = fileManifest,
            ),
          )
        }
        is MutatingCheckpointCompletionReady -> preparation.fileManifest
      }
    } ?: fileManifest
    recorder.retainProducerOutput(
      ProducerOutputEvidence(
        request.workflowId,
        run.phaseId,
        iteration,
        run.resolvedAgent.resolvedAgentId,
        run.modelDirective?.model ?: "unspecified",
        java.time.Instant.now(),
        outputByteSize,
        outputSha256,
        outputBytes.takeUnless { outputTruncated },
      ),
      run.request.dbPathOverride,
    )
    return persistAcceptedOutput(
      run,
      iteration,
      normalizedOutput,
      observability,
      checkpointManifest,
      repositoryFingerprint,
    )
  }

  @Suppress("ReturnCount")
  private fun prepareMutatingCheckpointCompletion(
    run: PhaseRun,
    output: Map<String, Any?>,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): MutatingCheckpointCompletionPreparation? {
    if (
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    ) {
      return null
    }
    val resolved = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
      ?: return MutatingCheckpointCompletionBlocked(
        "Mutating-phase completion cannot resolve the durable workflow-owned path inventory.",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    val claimedPaths = JsonSupport.anyToStringAnyMap(output["produced_outputs"])
      .orEmpty()["changed_paths"]
      .let { it as? List<*> }
      .orEmpty()
      .mapNotNull { it as? String }
      .map { it.replace('\\', '/').trim().removePrefix("./") }
      .filter(String::isNotBlank)
      .toSet()
    val currentRepositoryPathsResult = phaseGates.gitOperations.repositoryOwnedPaths(run.request.repoRoot)
    if (!currentRepositoryPathsResult.ok) {
      return MutatingCheckpointCompletionBlocked(
        "Mutating-phase completion could not capture the current repository paths: " +
          currentRepositoryPathsResult.error,
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    val currentRepositoryPaths = currentRepositoryPathsResult.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .toSet()
    val persistedPaths = resolved.workflowOwnedPaths.distinct().sorted()
    val checkpointPaths = if (
      persistedPaths.isEmpty() &&
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    ) {
      reconcileCheckpointPathInventory(
        repoRoot = run.request.repoRoot,
        specReference = run.request.runInvariants.specReference,
        specSource = run.specSource,
        paths = (currentRepositoryPaths + claimedPaths + fileManifest.introduced).sorted(),
      ).distinct().sorted()
    } else {
      (persistedPaths + currentRepositoryPaths + claimedPaths + fileManifest.introduced).distinct().sorted()
    }
    if (checkpointPaths.isEmpty()) {
      return MutatingCheckpointCompletionBlocked(
        "Mutating-phase completion has an empty durable workflow-owned path inventory.",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    val identities = phaseGates.gitOperations.scopedCheckpointOperations
      .ownedPathContentIdentities(run.request.repoRoot, checkpointPaths)
    if (!identities.ok) {
      return MutatingCheckpointCompletionBlocked(
        "Mutating-phase completion could not capture owned-path content identities: ${identities.error}",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    val parsedIdentities = identities.value.orEmpty()
      .lineSequence()
      .filter(String::isNotBlank)
      .associate { line -> line.substringBefore('\t') to line.substringAfter('\t') }
    if (
      parsedIdentities.keys != checkpointPaths.toSet() ||
      !recorder.recordWorkflowOwnedPaths(
        run.request.workflowId,
        checkpointPaths,
        parsedIdentities,
        run.request.dbPathOverride,
      )
    ) {
      return MutatingCheckpointCompletionBlocked(
        "Mutating-phase completion could not persist the complete owned-path content identities.",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    return MutatingCheckpointCompletionReady(fileManifest)
  }

  private fun nonCompactAuditDurableLedgerGateReason(
    phaseId: String,
    outputText: String,
    outputMap: Map<String, Any?>,
  ): String? = if (isCompactAuditOutput(phaseId, outputText)) null else auditDurableLedgerGateReason(phaseId, outputMap)

  private fun firstValidatedOutputRejection(
    phaseId: String,
    outputText: String,
    outputMap: Map<String, Any?>,
  ): Pair<String, String>? =
    mutatingReconciliationGateReason(phaseId, outputMap)?.let { "mutating-reconciliation" to it }
      ?: terminalAuditRepairBlockGateReason(phaseId, outputMap)?.let { "terminal-audit-repair" to it }
      ?: auditRepairResultGateReason(phaseId, outputMap)?.let { "audit-repair-result" to it }
      ?: nonCompactAuditDurableLedgerGateReason(phaseId, outputText, outputMap)
        ?.let { "audit-durable-ledger" to it }
      ?: auditClosedCriterionGateReason(phaseId, outputMap)?.let { "audit-closed-criterion" to it }

  /**
   * A completed producer must satisfy the exact projection its immediate forward consumer will parse.
   * This shares the launch assembler and validator instead of restating receipt shapes. Rejecting here
   * keeps malformed finalization receipts in the producer's bounded correction loop.
   */
  private fun immediateConsumerProjectionGateReason(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repositoryFingerprint: String?,
  ): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) return null
    val producerIndex = transitions.forwardPhaseIds.indexOf(run.phaseId)
    if (producerIndex < 0 || producerIndex == transitions.forwardPhaseIds.lastIndex) return null
    val consumerPhaseId = transitions.forwardPhaseIds[producerIndex + 1]
    val declaration = phaseDeclaration(consumerPhaseId, run.request.runInvariants.featureSize)
    val currentOutput = FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload = normalizedOutput.canonicalJson,
      normalizedOutput = normalizedOutput,
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

  private fun resolveCheckpointOwnedPaths(run: PhaseRun, persistedOwnedPaths: List<String>?): List<String>? {
    val currentPaths = checkpointOwnedPaths(run) ?: return null
    val checkpointPaths = (persistedOwnedPaths.orEmpty() + currentPaths).distinct()
    val inventory = reconcileCheckpointPathInventory(
      repoRoot = run.request.repoRoot,
      specReference = run.request.runInvariants.specReference,
      specSource = run.specSource,
      paths = checkpointPaths,
    ).sorted()
    if (inventory.isEmpty()) return null
    val contentIdentities = gitOperations.scopedCheckpointOperations
      .ownedPathContentIdentities(run.request.repoRoot, inventory)
      .takeIf { it.ok }?.value
      ?.lineSequence()
      ?.filter(String::isNotBlank)
      ?.associate { line -> line.substringBefore('\t') to line.substringAfter('\t') }
      ?: return null
    return inventory.takeIf {
      recorder.recordWorkflowOwnedPaths(
        run.request.workflowId,
        inventory,
        contentIdentities,
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
   * Paths for the checkpoint scope are the repository's current tracked and untracked changes.
   *
   * The listing uses NUL-delimited plumbing. `git status
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
  private fun checkpointOwnedPaths(run: PhaseRun): List<String>? {
    val owned = gitOperations.repositoryOwnedPaths(run.request.repoRoot)
    if (!owned.ok) return null
    val paths = owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
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
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ||
      run.reentry?.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    ) {
      return null
    }
    val prior = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride) ?: return null
    // Review no longer sits inside the reopened [implement, audit] span, so non-progress detection is
    // the only bound left on the uncapped audit-gap cycle. An absent fingerprint means repository
    // change could not be proven, which is not evidence that anything moved: treat it as unchanged so
    // an equivalent recurring gap set blocks, rather than disarming the bound and looping forever.
    val previousFingerprint = prior.repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT
    val currentFingerprint = repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val currentPlan = produced["audit_repair_plan"]?.let {
      auditRepairPlanFromWire(it, "audit.produced_outputs.audit_repair_plan")
    }
    val currentGapIds = currentPlan?.gaps.orEmpty().mapTo(linkedSetOf()) { it.gapId }
    if (currentGapIds.isEmpty()) return null
    val latestPlanItemIds = prior.acceptedPlans.last().gaps
      .flatMap { it.repairItems }
      .mapTo(linkedSetOf()) { it.repairItemId }
    val resolvedCount = prior.repairItemResults.count { it.repairItemId in latestPlanItemIds }
    return detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairGapIdentities(
        gapIds = prior.unresolvedGapLedger.unresolvedGaps.mapTo(linkedSetOf()) { it.gapId },
        criterionRefs = prior.unresolvedGapLedger.unresolvedGaps.mapTo(linkedSetOf()) { it.acceptanceCriterionRef },
      ),
      current = FeatureTaskRuntimeAuditRepairGapIdentities(
        gapIds = currentGapIds,
        criterionRefs = currentPlan?.gaps.orEmpty().mapTo(linkedSetOf()) { it.acceptanceCriterionRef },
      ),
      previousRepositoryFingerprint = previousFingerprint,
      currentRepositoryFingerprint = currentFingerprint,
      newlyResolvedRepairItemCount = resolvedCount,
    ).reason
  }

  private fun terminalOutputAttempt(
    run: PhaseRun,
    iteration: Int,
    reason: String,
    outputText: String,
    outputMap: Map<String, Any?>,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): AttemptResult {
    val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, outputMap)
    return if (
      disposition.retryOnResume &&
      FeatureTaskRuntimeFixLoopPolicy.participatesInFixLoop(run.phaseId)
    ) {
      AttemptResult.schemaInvalid(
        publicReason = reason,
        retryCorrectionReason = reason,
        fileManifest = fileManifest,
        rejectedOutput = null,
      )
    } else {
      AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = disposition,
          fileManifest = fileManifest,
          outputArtifact = outputText,
        ),
      )
    }
  }

  private fun outputVerificationGateReason(phaseId: String, outputMap: Map<String, Any?>): String? =
    reviewVerificationSignalGateReason(phaseId, outputMap)
      ?: auditVerificationSignalGateReason(phaseId, outputMap)

  private fun isCompactAuditOutput(phaseId: String, canonicalJson: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return false
    val wireOutput = JsonSupport.parseObjectOrNull(canonicalJson)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return false
    val produced = JsonSupport.anyToStringAnyMap(wireOutput["produced_outputs"]) ?: return false
    return produced.containsKey(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS)
  }

  @Suppress("ReturnCount", "CyclomaticComplexMethod")
  private fun auditRepairResultGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    val reentry = activeReentry?.takeIf {
      it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    } ?: return null
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) return null
    val expected = reentry.auditRepairPlan?.gaps.orEmpty()
      .flatMap { it.repairItems }
      .map { it.repairItemId }
    if (expected.isEmpty()) return "Audit-gap remediation is missing its persisted audit_repair_plan."
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val results = (produced["repair_item_results"] as? List<*>).orEmpty()
    val resultMaps = results.mapNotNull(JsonSupport::anyToStringAnyMap)
    val actual = resultMaps.mapNotNull { (it["repair_item_id"] as? String)?.let(::canonicalAuditIdentifier) }
    val blocked = outputMap["status"] == STATUS_BLOCKED
    val deferredRaw = produced["deferred_repair_item_ids"]
    if (deferredRaw !is List<*>) {
      return structuredRepairDiagnostic(
        "audit_repair.deferred_work.required",
        "/produced_outputs/deferred_repair_item_ids",
        "Audit-gap remediation must explicitly report deferred repair-item identifiers.",
      )
    }
    val deferred = deferredRaw.mapNotNull { (it as? String)?.let(::canonicalAuditIdentifier) }
    if (deferred.size != deferredRaw.size || deferred.size != deferred.toSet().size ||
      deferred.any { it !in expected }
    ) {
      return structuredRepairDiagnostic(
        "audit_repair.deferred_work.identifiers",
        "/produced_outputs/deferred_repair_item_ids",
        "Deferred repair-item identifiers must be unique exact identifiers from the accepted plan; " +
          "expected=$expected actual=$deferred.",
      )
    }
    if (!blocked && deferred.isNotEmpty()) {
      return structuredRepairDiagnostic(
        "audit_repair.completed.deferred_work",
        "/produced_outputs/deferred_repair_item_ids",
        "Completed audit-gap remediation requires an empty deferred-repair representation.",
      )
    }
    if (!blocked && produced.containsKey("unresolvable_repair")) {
      return structuredRepairDiagnostic(
        "audit_repair.completed.unresolvable_repair",
        "/produced_outputs/unresolvable_repair",
        "Only blocked audit-gap remediation may identify an unresolvable repair item.",
      )
    }
    val identifiersInvalid = actual.size != resultMaps.size || actual.size != actual.toSet().size ||
      if (blocked) {
        actual.toSet() + deferred.toSet() != expected.toSet() ||
          actual.toSet().intersect(deferred.toSet()).isNotEmpty()
      } else {
        actual.toSet() != expected.toSet()
      }
    if (identifiersInvalid) {
      return structuredRepairDiagnostic(
        "audit_repair.results.identifiers",
        "/produced_outputs/repair_item_results",
        "Audit-gap remediation results and deferred identifiers must exhaust the accepted plan exactly once; " +
          "expected=$expected actual=$actual deferred=$deferred.",
      )
    }
    val expectedOrder = expected.withIndex().associate { (index, id) -> id to index }
    val actualOrder = actual.withIndex().associate { (index, id) -> id to index }
    val planItems = reentry.auditRepairPlan?.gaps.orEmpty().flatMap { it.repairItems }
    planItems.forEach { item ->
      val itemId = item.repairItemId
      val itemPosition = actualOrder[itemId] ?: return@forEach
      item.dependsOn.forEach { dependency ->
        val dependencyPosition = actualOrder[dependency] ?: return structuredRepairDiagnostic(
          "audit_repair.results.dependency_terminal",
          "/produced_outputs/repair_item_results/$itemPosition/repair_item_id",
          "Repair item '$itemId' cannot be terminal while dependency '$dependency' is deferred or missing.",
        )
        val expectedDependency = expectedOrder[dependency] ?: return@forEach
        val expectedItem = expectedOrder[itemId] ?: return@forEach
        if (dependencyPosition >= itemPosition || expectedDependency >= expectedItem) {
          return structuredRepairDiagnostic(
            "audit_repair.results.dependency_order",
            "/produced_outputs/repair_item_results/$itemPosition/repair_item_id",
            "Repair item '$itemId' depends on '$dependency', which must have a preceding terminal result.",
          )
        }
      }
    }
    resultMaps.forEachIndexed { index, result ->
      auditRepairResultError(result, index)?.let { return it }
    }
    if (blocked) {
      val unresolvable = JsonSupport.anyToStringAnyMap(
        produced["unresolvable_repair"],
      )
      val blockedItemId = (unresolvable?.get("repair_item_id") as? String)?.let(::canonicalAuditIdentifier)
      if (blockedItemId == null || blockedItemId !in deferred) {
        return structuredRepairDiagnostic(
          "audit_repair.blocked.deferred_item",
          "/produced_outputs/deferred_repair_item_ids",
          "Blocked remediation must include the item named by unresolvable_repair among its remaining work.",
        )
      }
    }
    return null
  }

  private fun auditRepairResultError(result: Map<String, Any?>, index: Int): String? {
    val label = (result["repair_item_id"] as? String)?.takeIf(String::isNotBlank)
      ?: "repair_item_results[$index]"
    val expectedKeys = setOf(
      "repair_item_id",
      "outcome",
      "changed_paths_or_symbols",
      "executed_verification",
      "result_evidence",
    )
    val missing = expectedKeys - result.keys
    val unknown = result.keys - expectedKeys
    val decodeFailure = runCatching { repairItemResultFromWire(result, "repair_item_results[$index]") }
      .exceptionOrNull()
    return when {
      missing.isNotEmpty() || unknown.isNotEmpty() ->
        structuredRepairDiagnostic(
          "audit_repair.results.shape",
          "/produced_outputs/repair_item_results/$index",
          "Repair item '$label' has invalid fields; missing=${missing.sorted()} unknown=${unknown.sorted()}.",
        )
      result["outcome"] !in setOf("fixed", "already_satisfied") ->
        structuredRepairDiagnostic(
          "audit_repair.results.terminal_outcome",
          "/produced_outputs/repair_item_results/$index/outcome",
          "Repair item '$label' outcome must be fixed or already_satisfied.",
        )
      hasNoNonBlankStrings(result["changed_paths_or_symbols"]) ->
        structuredRepairDiagnostic(
          "audit_repair.results.repository_evidence",
          "/produced_outputs/repair_item_results/$index/changed_paths_or_symbols",
          "Repair item '$label' must name at least one changed path or symbol.",
        )
      hasNoNonBlankStrings(result["executed_verification"]) ->
        structuredRepairDiagnostic(
          "audit_repair.results.executed_verification",
          "/produced_outputs/repair_item_results/$index/executed_verification",
          "Repair item '$label' must report at least one executed verification and result.",
        )
      decodeFailure != null ->
        structuredRepairDiagnostic(
          "audit_repair.results.result_evidence",
          "/produced_outputs/repair_item_results/$index/result_evidence",
          "Repair item '$label' is not contract-safe: ${decodeFailure.diagnosticMessage()}",
        )
      result["outcome"] == "already_satisfied" && !alreadySatisfiedEvidenceIsDistinct(result) ->
        structuredRepairDiagnostic(
          "audit_repair.results.distinct_evidence",
          "/produced_outputs/repair_item_results/$index",
          "Repair item '$label' must distinguish repository evidence from executed verification.",
        )
      else -> null
    }
  }

  private fun structuredRepairDiagnostic(ruleId: String, jsonPath: String, detail: String): String =
    "[$ruleId] $jsonPath: $detail"

  private fun structuredRepairDiagnosticIdentity(detail: String): Pair<String, String>? =
    Regex("""^\[([A-Za-z0-9_.-]+)]\s+(/[^\s:]*):\s""")
      .find(detail)
      ?.destructured
      ?.let { (ruleId, jsonPath) -> ruleId to jsonPath }

  private fun retryCorrectionReason(
    detail: String,
    structuredIdentity: Pair<String, String>?,
    publicReason: String,
  ): String = if (structuredIdentity == null) publicReason else detail

  private fun hasNoNonBlankStrings(value: Any?): Boolean =
    (value as? List<*>)?.filterIsInstance<String>()?.none(String::isNotBlank) != false

  private fun alreadySatisfiedEvidenceIsDistinct(result: Map<String, Any?>): Boolean {
    val repositoryEvidence = (result["changed_paths_or_symbols"] as? List<*>)
      .orEmpty().filterIsInstance<String>().filter(String::isNotBlank)
    val verificationEvidence = (result["executed_verification"] as? List<*>)
      .orEmpty().filterIsInstance<String>().filter(String::isNotBlank)
    return repositoryEvidence.isNotEmpty() && verificationEvidence.isNotEmpty() &&
      repositoryEvidence.toSet() != verificationEvidence.toSet()
  }

  @Suppress("ReturnCount")
  private fun auditDurableLedgerGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val repairState = recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)
    val priorIds = repairState?.unresolvedGapLedger?.unresolvedGaps.orEmpty()
      .mapTo(linkedSetOf()) { it.gapId }
    if (priorIds.isEmpty()) return null
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val dispositions = (produced["prior_gap_dispositions"] as? List<*>).orEmpty()
      .mapNotNull(JsonSupport::anyToStringAnyMap)
    val dispositionIds = dispositions.mapNotNull { it["gap_id"] as? String }
    if (dispositionIds.size != dispositions.size || dispositionIds.toSet() != priorIds) {
      return "The following audit must disposition every durable unresolved gap exactly once; " +
        "expected=$priorIds actual=$dispositionIds."
    }
    dispositions.forEachIndexed { index, disposition ->
      val decodeFailure = runCatching {
        priorGapDispositionFromWire(disposition, "prior_gap_dispositions[$index]")
      }.exceptionOrNull()
      if (decodeFailure != null) {
        val gapId = disposition["gap_id"] as? String ?: "prior_gap_dispositions[$index]"
        return "Prior gap disposition '$gapId' is not contract-safe: ${decodeFailure.diagnosticMessage()}"
      }
    }
    val recurring = dispositions.filter { it["status"] == "recurring" }
    if (recurring.size + dispositions.count { it["status"] == "resolved" } != dispositions.size) {
      return "Prior gap dispositions must be resolved or recurring."
    }
    if (outputMap["verdict"] == "satisfied" && recurring.isNotEmpty()) {
      return "An audit cannot report satisfied while the durable unresolved-gap ledger remains non-empty."
    }
    return null
  }

  // Closure is only durable if nothing can quietly reopen it: an audit naming a closed criterion under
  // any gap id, or naming a criterion the run never declared, is rejected here rather than reconciled.
  private fun auditClosedCriterionGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val planRefs = (JsonSupport.anyToStringAnyMap(produced["audit_repair_plan"])?.get("gaps") as? List<*>)
      .orEmpty()
      .mapNotNull { gap -> JsonSupport.anyToStringAnyMap(gap)?.get("acceptance_criterion_ref") as? String }
    val criteriaRefs = (produced["unmet_criteria"] as? List<*>).orEmpty()
      .mapNotNull { entry -> JsonSupport.anyToStringAnyMap(entry)?.get("acceptance_criterion_ref") as? String }
    val referenced = (planRefs + criteriaRefs).distinct()
    if (referenced.isEmpty()) return null
    val undeclared = referenced.filterNot { it in declaredCriterionRefs().toSet() }.sorted()
    if (undeclared.isNotEmpty()) {
      return "Audit reported acceptance criteria not declared by this run: $undeclared."
    }
    val reopened = referenced.filter { it in durablyClosedCriterionRefs().toSet() }.sorted()
    return if (reopened.isEmpty()) {
      null
    } else {
      "Audit reported a gap against durably closed acceptance criteria $reopened; a criterion that reached a " +
        "satisfied verdict is closed and is not re-verified by a later audit."
    }
  }

  private fun Throwable.diagnosticMessage(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.simpleName.orEmpty().ifBlank { "unknown decode failure" }

  @Suppress("ReturnCount")
  private fun terminalAuditRepairBlockGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
    if (outputMap["status"] != STATUS_BLOCKED) return null
    val reentry = activeReentry?.takeIf {
      it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    } ?: return null
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) return null
    val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
    val block = JsonSupport.anyToStringAnyMap(produced["unresolvable_repair"])
      ?: return structuredRepairDiagnostic(
        "audit_repair.blocked.unresolvable_repair.required",
        "/produced_outputs/unresolvable_repair",
        "Blocked audit remediation must persist an object with gap_id and repair_item_id.",
      )
    val gapId = block["gap_id"] as? String
    val itemId = block["repair_item_id"] as? String
    if (gapId.isNullOrBlank()) {
      return structuredRepairDiagnostic(
        "audit_repair.blocked.gap_id",
        "/produced_outputs/unresolvable_repair/gap_id",
        "Blocked audit remediation requires a nonblank gap_id from the accepted repair plan.",
      )
    }
    if (itemId.isNullOrBlank()) {
      return structuredRepairDiagnostic(
        "audit_repair.blocked.repair_item_id",
        "/produced_outputs/unresolvable_repair/repair_item_id",
        "Blocked audit remediation requires a nonblank repair_item_id from the named gap.",
      )
    }
    runCatching {
      auditEvidenceFromWire(block["evidence"], "unresolvable_repair.evidence")
    }.exceptionOrNull()?.let { decodeFailure ->
      return structuredRepairDiagnostic(
        "audit_repair.blocked.evidence",
        "/produced_outputs/unresolvable_repair/evidence",
        "Blocked audit remediation evidence is not contract-safe: ${decodeFailure.diagnosticMessage()}",
      )
    }
    val owningGap = reentry.auditRepairPlan?.gaps.orEmpty().firstOrNull { it.gapId == gapId }
      ?: return structuredRepairDiagnostic(
        "audit_repair.blocked.gap_id",
        "/produced_outputs/unresolvable_repair/gap_id",
        "Blocked audit remediation references unknown gap_id '$gapId'.",
      )
    val carriedItems = owningGap.repairItems.map { it.repairItemId }
    return if (itemId !in carriedItems) {
      structuredRepairDiagnostic(
        "audit_repair.blocked.repair_item_id",
        "/produced_outputs/unresolvable_repair/repair_item_id",
        "Blocked audit remediation references repair_item_id '$itemId' outside gap '$gapId'.",
      )
    } else {
      null
    }
  }

  private fun validateImplementationCompletion(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    @Suppress("UNUSED_PARAMETER") repositoryFingerprint: String?,
  ): AttemptResult? {
    val receipt = implementationReceiptFromOutput(normalizedOutput.envelope)
      ?: return null
    val authoritativePlan = state.authoritativeExecutablePlan()
      ?: return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          "Implementation completion validation requires the authoritative executable plan.",
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          fileManifest = fileManifest,
        ),
      )
    val unresolvedObligations = recorder.loadConvergenceState(request.workflowId, request.dbPathOverride)
      ?: UnresolvedConvergence(
        implementationObligations = emptyList(),
        auditRepairs = emptyList(),
        reviewBlockers = emptyList(),
      )
    val decision = implementationCompletionDecisionFromContext(
      authoritativeExecutablePlan = authoritativePlan,
      unresolvedObligations = unresolvedObligations,
      receipt = receipt,
    )
    return when {
      decision.outcome is ImplementationCompleted -> null
      decision.disposition is SemanticIncompleteWorkContinuation -> {
        recorder.recordPhaseState(
          phaseStateRequest(
            run = run,
            iteration = iteration,
            status = STATUS_RUNNING,
            finished = false,
            outputArtifact = normalizedOutput.canonicalJson,
            fileManifest = fileManifest,
            normalizedOutput = normalizedOutput,
            repositoryFingerprint = repositoryFingerprint,
          ),
          run.request.dbPathOverride,
        )
        AttemptResult.semanticIncomplete(
          implementationContinuationReason(decision),
          fileManifest = fileManifest,
        )
      }
      decision.disposition is SchemaInvalidCorrection -> {
        AttemptResult.settled(
          blockAndPersistInPhase(
            run,
            iteration,
            "Implementation receipt has schema-invalid output: " +
              "${(decision.disposition as SchemaInvalidCorrection).schemaViolation}",
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
            fileManifest = fileManifest,
          ),
        )
      }
      decision.disposition is TerminalBlocked -> {
        AttemptResult.settled(
          blockAndPersistInPhase(
            run,
            iteration,
            (decision.disposition as TerminalBlocked).reason,
            observability,
            failureDisposition = (decision.disposition as TerminalBlocked).disposition,
            fileManifest = fileManifest,
          ),
        )
      }
      else -> AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          decision.exactBlockingReason() ?: "Implementation completion denied.",
          observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          fileManifest = fileManifest,
        ),
      )
    }
  }

  private fun implementationContinuationReason(decision: ImplementationCompletionDecision): String {
    val incomplete = decision.outcome as ImplementationIncomplete
    val continuation = decision.disposition as SemanticIncompleteWorkContinuation
    val receipt = continuation.priorReceipt
    val details = buildList {
      if (incomplete.missingTaskIds.isNotEmpty()) {
        add("missing plan task IDs: ${incomplete.missingTaskIds.joinToString()}")
      }
      if (incomplete.unknownTaskIds.isNotEmpty()) {
        add("unknown completed task IDs: ${incomplete.unknownTaskIds.joinToString()}")
      }
      if (incomplete.unresolvedItems.isNotEmpty()) {
        add("unresolved items: ${incomplete.unresolvedItems.joinToString()}")
      }
      if (incomplete.actionableDeviations.isNotEmpty()) {
        add(
          "actionable deviations: " +
            incomplete.actionableDeviations.joinToString { "${it.ref}: ${it.note}" },
        )
      }
      if (incomplete.openObligationIds.isNotEmpty()) {
        add("open durable obligations: ${incomplete.openObligationIds.joinToString()}")
      }
    }
    val boundedReceipt = listOf(
      "completed_task_ids=${receipt.completedTaskIds.joinToString(prefix = "[", postfix = "]")}",
      "changed_paths=${receipt.changedPaths.joinToString(prefix = "[", postfix = "]")}",
      "tests_added=${receipt.testsAdded.joinToString(prefix = "[", postfix = "]")}",
      "tests_updated=${receipt.testsUpdated.joinToString(prefix = "[", postfix = "]")}",
      "deviations=${receipt.deviations.joinToString(prefix = "[", postfix = "]") { "${it.ref}: ${it.note}" }}",
      "unresolved_items=${receipt.unresolvedItems.joinToString(prefix = "[", postfix = "]")}",
      "reconciliation_evidence=${receipt.reconciliationEvidence.evidence}",
      "repository_checkpoint=${receipt.repositoryCheckpoint.fingerprint}",
      "failure_disposition=${continuation.failureDisposition.wireValue}",
    ).joinToString("; ")
    return "Implementation incomplete; continue the implementation from the durable prior receipt. " +
      "${details.joinToString("; ")}. Prior receipt: $boundedReceipt"
  }

  @Suppress("ReturnCount")
  private fun persistAcceptedOutput(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    repositoryFingerprint: String?,
  ): AttemptResult {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
    if (isGoalReviewRun(run)) {
      persistGoalReviewCompletion(run, iteration, normalizedOutput, observability, fileManifest)?.let { outcome ->
        return AttemptResult.settled(outcome)
      }
    } else if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT) {
      validateImplementationCompletion(
        run,
        iteration,
        normalizedOutput,
        observability,
        fileManifest,
        repositoryFingerprint,
      )?.let { result ->
        return result
      }
      val persisted = recorder.recordCompletedPhase(
        phaseStateRequest(
          run,
          iteration,
          STATUS_COMPLETED,
          finished = true,
          outputArtifact = outputText,
          fileManifest = fileManifest,
          normalizedOutput = normalizedOutput,
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
      PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, outputText, normalizedOutput)),
    )
  }

  private fun persistGoalReviewCompletion(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome? {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap)
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
            repositoryFingerprint = reviewedCheckpointFingerprint(),
          ),
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = outputText,
          blockerDispositions = GoalSubtaskReviewSummaryReducer.blockerDispositions(
            outputMap,
            priorBlockerFindingIds(),
          ),
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

  @Suppress("SwallowedException")
  private fun implementationReceiptFromOutput(output: Map<String, Any?>): FeatureTaskRuntimeImplementationReceipt? {
    val produced = JsonSupport.anyToStringAnyMap(output["produced_outputs"]) ?: return null
    val kind = produced["projection_kind"]?.toString() ?: return null
    if (kind != "implementation_receipt") return null
    return try {
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = output,
        producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        expectedKind = FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT,
        schemaValidator = dependencies.phaseGates.planningProjectionValidator,
      ) as? FeatureTaskRuntimeImplementationReceipt
    } catch (error: IllegalArgumentException) {
      null
    } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
      null
    }
  }

  // A goal-subtask review reserves its pass once in prepareGoalReviewRun, outside runPhaseAttempts, so a
  // bounded in-loop re-attempt reuses that same reserved pass instead of allocating another. Schema-invalid
  // output therefore earns the same fix-loop retries as every other phase: the reserved pass has no completed
  // output, which is the state a resume is already contracted to re-enter rather than treat as terminal.
  private fun schemaInvalidAttempt(
    publicReason: String,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    malformedOutput: Boolean = false,
    retryCorrectionReason: String = publicReason,
  ): AttemptResult = AttemptResult.schemaInvalid(
    publicReason,
    retryCorrectionReason,
    fileManifest,
    null,
    malformedOutput,
  )

  private fun persistPhase(
    run: PhaseRun,
    iteration: Int,
    status: String,
    finished: Boolean,
    outputArtifact: String?,
    fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  ) {
    val phaseState = phaseStateRequest(run, iteration, status, finished, outputArtifact, fileManifest)
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
    repositoryFingerprint: String? = null,
  ): FeatureTaskRuntimePhaseStateRequest = FeatureTaskRuntimePhaseStateRequest(
    workflowId = run.request.workflowId,
    phaseId = run.phaseId,
    status = status,
    attemptCount = iteration,
    resolvedAgentId = run.resolvedAgent.resolvedAgentId,
    finished = finished,
    outputArtifact = outputArtifact,
    normalizedOutput = normalizedOutput,
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
  )

  private fun reviewPassNumber(run: PhaseRun, state: FeatureTaskRuntimeRunState): Int? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    val currentPass = state.currentReviewPassNumber()
    return if (currentPass == 2 || state.outputCountFor(run.phaseId) > 0) 2 else currentPass ?: 1
  }

  private fun completedReviewBudgetOutput(
    phaseId: String,
    state: FeatureTaskRuntimeRunState,
  ): FeatureTaskRuntimePhaseOutput? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    val budgetCompleted = state.completedReviewPassNumber() == 2 || state.outputCountFor(phaseId) >= 2
    return state.outputFor(phaseId)?.takeIf { budgetCompleted }
  }

  private fun prepareLaunch(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorSchemaFailure: String?,
    durablyClosedCriterionRefs: List<String>,
    repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ): PreparedLaunch {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = run.declaration,
      runInvariants = run.request.runInvariants,
      recordedOutputs = state.outputs(),
      drivingVerdict = run.reentry?.drivingVerdict,
      reentryGapCriteria = auditGapCriteriaFor(run, state),
      auditRepairPlan = run.reentry?.auditRepairPlan,
      auditRepairState = run.reentry?.auditRepairState,
      durablyClosedCriterionRefs = durablyClosedCriterionRefs,
      repositoryCheckpoint = repositoryCheckpoint,
      expectedRepositoryCheckpoint = (
        if (
          run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
          run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
        ) {
          repositoryCheckpoint?.fingerprint
        } else {
          run.reentry?.expectedRepositoryCheckpoint
            ?: run.reentry?.auditRepairState?.repositoryFingerprint
            ?: repositoryCheckpoint?.fingerprint
        }
        )
        ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
      branchIdentity = resolvedBranch,
      baseBranch = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
        ?.baseBranch
        ?: "main",
    )
    recorder.validateHandoffDeclarations(handoff.projectionDeclarations)
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      run.request.workflowId,
      planningProjectionValidator,
      run.request.agentAddonSelection,
    )
    recorder.recordPhaseBriefing(run.request.workflowId, briefing, run.request.dbPathOverride)
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
      resolvedReviewTier = depthResolution?.resolvedTier,
      reviewDecidingRule = depthResolution?.decidingRule,
      priorBlockerFindingIds = priorBlockerFindingIds(passNumber),
      carriedBlockerFindings = if (passNumber != null) {
        recorder.unresolvedReviewBlockers(request.workflowId, request.dbPathOverride)
      } else {
        emptyList()
      },
      specSource = run.specSource,
      priorSchemaFailure = priorSchemaFailure ?: durableImplementationContinuationReason(run, state),
      operatorBlockRetry = operatorBlockRetry
        ?.takeIf { it.phaseId == run.phaseId && !operatorBlockRetryCompleted },
      specReference = run.request.runInvariants.specReference,
    )
    return PreparedLaunch(briefing, prompt)
  }

  private fun durableImplementationContinuationReason(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT) return null
    val envelope = recorder.loadPriorImplementationReceiptEnvelope(
      run.request.workflowId,
      run.request.dbPathOverride,
    ) ?: return null
    val receipt = implementationReceiptFromOutput(envelope) ?: return null
    val plan = state.authoritativeExecutablePlan() ?: return null
    val unresolved = recorder.loadConvergenceState(run.request.workflowId, run.request.dbPathOverride)
      ?: UnresolvedConvergence(emptyList(), emptyList(), emptyList())
    val decision = implementationCompletionDecisionFromContext(plan, unresolved, receipt)
    return if (decision.disposition is SemanticIncompleteWorkContinuation) {
      implementationContinuationReason(decision)
    } else {
      null
    }
  }

  @Suppress("ReturnCount")
  private fun launchAndCapture(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorSchemaFailure: String? = null,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): LaunchResult {
    val before = gitOperations.worktreeStatus(run.request.repoRoot)
    if (!before.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its before-file manifest: ${before.error}",
      )
    }
    val beforeCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!beforeCommit.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its before commit: ${beforeCommit.error}",
      )
    }
    val prepared = when (val preparation = prepareLaunchForCapture(run, state, priorSchemaFailure)) {
      is PreparedLaunchReady -> preparation.value
      is LaunchPreparationRejected -> return preparation.result
      else -> error("Unexpected launch preparation result.")
    }
    val briefing = prepared.briefing
    val isReviewPhase = run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW

    // Cursor requires model and effort to be merged into bracket syntax at the request level
    val (modelOverride, effortOverride) = if (run.resolvedAgent.resolvedAgentId == InstallAgent.CURSOR.id) {
      val model = run.modelDirective?.model
      val effort = run.modelDirective?.effort
      if (model != null && effort != null) {
        "$model[effort=$effort]" to effort
      } else {
        model to effort
      }
    } else {
      run.modelDirective?.model to run.modelDirective?.effort
    }

    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = run.resolvedAgent.invokedAgentId,
        configuredAgentOverrideId = run.resolvedAgent.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = run.request.issueKey,
          repoRoot = run.request.repoRoot,
          dbPathOverride = run.request.dbPathOverride,
          timeout = run.request.timeout,
          modelOverride = modelOverride,
          effortOverride = effortOverride,
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
      )
    }
    val afterCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!afterCommit.ok) {
      return LaunchResult.infraFailure(
        "Feature-task-runtime phase '${run.phaseId}' could not capture its after commit: ${afterCommit.error}",
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
      )
    }
    val fileManifest = FeatureTaskRuntimePhaseFileManifest(
      before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.value),
      after = (
        FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value) +
          FeatureTaskRuntimePhaseSafetyPolicy.lineSeparatedPaths(committedPaths.value.orEmpty())
        ).distinct().sorted(),
    )
    return reconcileLaunch(run.phaseId, outcome, fileManifest)
  }

  private fun prepareLaunchForCapture(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorSchemaFailure: String?,
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
      priorSchemaFailure,
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
    priorSchemaFailure: String?,
    durablyClosedCriterionRefs: List<String>,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparation = try {
    PreparedLaunchReady(
      prepareLaunch(
        run,
        state,
        priorSchemaFailure,
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

  private sealed interface MutatingCheckpointCompletionPreparation

  private data class MutatingCheckpointCompletionReady(
    val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : MutatingCheckpointCompletionPreparation

  private data class MutatingCheckpointCompletionBlocked(
    val reason: String,
    val disposition: FeatureTaskRuntimeFailureDisposition,
  ) : MutatingCheckpointCompletionPreparation

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
    )
    is AgentRunLaunchFacts -> infraFailureReason(phaseId, outcome)
      ?.let { LaunchResult.infraFailure(it, fileManifest) }
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
    ) : LaunchResult
    private data class RecordRejected(
      val rejection: RecordRejection,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    ) : LaunchResult

    val capturedStdout: String? get() = (this as? Captured)?.stdout
    val capturedStdoutBytes: ByteArray? get() = (this as? Captured)?.stdoutBytes
    val capturedStdoutTruncated: Boolean get() = (this as? Captured)?.stdoutTruncated == true
    val capturedStdoutByteSize: Long? get() = (this as? Captured)?.stdoutByteSize
    val capturedStdoutSha256: String? get() = (this as? Captured)?.stdoutSha256
    val infraFailureReason: String? get() = (this as? InfraFailure)?.reason
    val recordRejection: RecordRejection? get() = (this as? RecordRejected)?.rejection
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
      fun infraFailure(reason: String, fileManifest: FeatureTaskRuntimePhaseFileManifest? = null): LaunchResult =
        InfraFailure(reason, fileManifest, FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE)

      /** Static declaration or configuration drift: retrying without operator action reproduces it. */
      fun projectionRejected(reason: String): LaunchResult =
        InfraFailure(reason, null, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION)

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
    private data class SemanticIncomplete(
      val reason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ) : AttemptResult
    private data class SchemaInvalid(
      val publicReason: String,
      val retryCorrectionReason: String,
      override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
      override val rejectedOutput: String?,
      override val malformedOutput: Boolean,
    ) : AttemptResult

    val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
    val semanticIncompleteReason: String? get() = (this as? SemanticIncomplete)?.reason
    val schemaInvalidReason: String? get() = (this as? SchemaInvalid)?.publicReason
    val schemaRetryCorrectionReason: String? get() = (this as? SchemaInvalid)?.retryCorrectionReason
    val fileManifest: FeatureTaskRuntimePhaseFileManifest? get() = (this as? SchemaInvalid)?.fileManifest
    val rejectedOutput: String? get() = (this as? SchemaInvalid)?.rejectedOutput
    val malformedOutput: Boolean get() = (this as? SchemaInvalid)?.malformedOutput == true

    companion object {
      fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)
      fun semanticIncomplete(reason: String, fileManifest: FeatureTaskRuntimePhaseFileManifest): AttemptResult =
        SemanticIncomplete(reason, fileManifest)
      fun schemaInvalid(
        publicReason: String,
        retryCorrectionReason: String,
        fileManifest: FeatureTaskRuntimePhaseFileManifest,
        rejectedOutput: String?,
        malformedOutput: Boolean = false,
      ): AttemptResult =
        SchemaInvalid(publicReason, retryCorrectionReason, fileManifest, rejectedOutput, malformedOutput)
    }
  }

  private sealed interface PhaseOutcome {
    private data class Completed(val output: FeatureTaskRuntimePhaseOutput) : PhaseOutcome
    private data class Blocked(val reason: String) : PhaseOutcome

    // SKILL-140: the consumer rejected an upstream producer's record and quarantined it; the run
    // settles the consumer with the RECORD_REJECTED verdict so the existing transition machinery
    // re-enters this producer under a bounded regeneration cap.
    private data class RegenerateProducer(val producerPhaseId: String) : PhaseOutcome

    val completedOutput: FeatureTaskRuntimePhaseOutput? get() = (this as? Completed)?.output

    val blockedReason: String? get() = (this as? Blocked)?.reason

    val regenerationTargetPhaseId: String? get() = (this as? RegenerateProducer)?.producerPhaseId

    companion object {
      fun completed(output: FeatureTaskRuntimePhaseOutput): PhaseOutcome = Completed(output)
      fun blocked(reason: String): PhaseOutcome = Blocked(reason)
      fun regenerateProducer(producerPhaseId: String): PhaseOutcome = RegenerateProducer(producerPhaseId)
    }
  }

  private sealed interface GoalReviewRunPreparation {
    data object CarryForward : GoalReviewRunPreparation
    data object Blocked : GoalReviewRunPreparation
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

// The block reason a pre-quarantine build persisted when a launch seam rejected an upstream bounded
// planning projection. The current seam quarantines the record and regenerates its producer instead,
// so this phrase is emitted by no live path and only ever matches a legacy durable row.
private const val LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION =
  "rejected an upstream bounded planning projection at the launch seam"

// NUL delimiter of the `-z` plumbing listing the checkpoint owned-path inventory is derived from.
private const val OWNED_PATH_DELIMITER = '\u0000'

// Bounds the rendered checkpoint scope well under the briefing framing ceiling, so an oversized
// inventory is rejected as a typed projection failure instead of tripping that ceiling's untyped throw.
private const val MAX_CHECKPOINT_OWNED_PATHS = 500
