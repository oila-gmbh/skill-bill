package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.carriedForwardGoalReviewSettlement(): PhaseSettlement? = runCatching {
  goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
}.fold(
  onSuccess = { reviewState ->
    reviewState
      ?.takeIf { it.reviewCapReached || it.reviewSkippedByUser }
      ?.let {
        settleCarriedForwardGoalReview(
          it,
          activeReentry,
        )
      }
  },
  onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
)

internal fun FeatureTaskRuntimeRunLoop.settleCarriedForwardGoalReview(
  reviewState: GoalSubtaskReviewState,
  reentry: PendingReentry?,
): PhaseSettlement =
  runCatching { goalContinuationRecorder.lastGoalReviewResult(request.workflowId, request.dbPathOverride) }.fold(
    onSuccess = { rawResult ->
      rawResult?.let { validateCarriedForwardGoalReview(it, reviewState, reentry) }
        ?: blockCarriedForwardReview("missing")
    },
    onFailure = { error -> blockCarriedForwardReview(error.message.orEmpty()) },
  )

internal fun FeatureTaskRuntimeRunLoop.validateCarriedForwardGoalReview(
  rawResult: String,
  reviewState: GoalSubtaskReviewState,
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

internal fun FeatureTaskRuntimeRunLoop.recordCarriedForwardGoalReview(
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
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

internal fun FeatureTaskRuntimeRunLoop.blockCarriedForwardReview(detail: String): PhaseSettlement {
  val reason = if (detail == "missing") {
    "Goal-subtask review pass budget is exhausted but its durable raw review result is missing."
  } else {
    "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: $detail"
  }
  blockAt(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, reason)
  return PhaseSettlement.stop()
}

internal fun FeatureTaskRuntimeRunLoop.reSurfaceAuditGapPause(pause: FeatureTaskRuntimeAuditGapPause) {
  pauseAt(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    pause.reason,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
  )
}

internal enum class AuditGapDriveAction { Continue, Stop }

internal fun FeatureTaskRuntimeRunLoop.invalidateReviewGenerationIfNeeded() {
  if (FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in state.phasesRequiringDurableGateInvalidation()) {
    return
  }
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

internal fun FeatureTaskRuntimeRunLoop.loadMigratedAuditGapPause(): FeatureTaskRuntimeAuditGapPause? =
  recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)?.let { pause ->
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

internal fun FeatureTaskRuntimeRunLoop.resolveAuditGapPauseDriveAction(
  auditGapPause: FeatureTaskRuntimeAuditGapPause,
): AuditGapDriveAction {
  when (auditGapPause.operatorDecision) {
    AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK -> {
      abandonAuditGapSubtask(auditGapPause)
      return AuditGapDriveAction.Stop
    }
    AUDIT_GAP_PAUSE_DECISION_RETRY_FIX -> {
      if (!auditGapPause.grantConsumed) {
        auditGapRetryResumePending = true
      }
      return AuditGapDriveAction.Continue
    }
    else -> {
      if (pendingReentry == null && !auditGapPause.grantConsumed) {
        reSurfaceAuditGapPause(auditGapPause)
        return AuditGapDriveAction.Stop
      }
    }
  }
  return AuditGapDriveAction.Continue
}

internal fun FeatureTaskRuntimeRunLoop.validateAuditGapResumeOrBlock(): Boolean {
  val resumedReentry = pendingReentry
  if (
    resumedReentry?.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID ||
    resumedReentry.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
  ) {
    return true
  }
  val reason = state.auditGapPlanningContextError() ?: return true
  blockInvalidAuditGapRecovery(resumedReentry, reason)
  return false
}
