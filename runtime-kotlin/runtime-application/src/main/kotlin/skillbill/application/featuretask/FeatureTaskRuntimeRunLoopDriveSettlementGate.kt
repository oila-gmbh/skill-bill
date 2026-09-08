package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause

@Inject
class FeatureTaskRuntimeRunLoopDriveSettlementGate {
  internal fun blockCarriedForwardReview(runLoop: FeatureTaskRuntimeRunLoop, detail: String): PhaseSettlement {
    val reason = if (detail == "missing") {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is missing."
    } else {
      "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: $detail"
    }
    runLoop.collaborators.planningBranch.blockAt(
      runLoop,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      reason,
    )
    return PhaseSettlement.stop()
  }

  fun reSurfaceAuditGapPause(runLoop: FeatureTaskRuntimeRunLoop, pause: FeatureTaskRuntimeAuditGapPause) {
    runLoop.collaborators.planningBranch.pauseAt(
      runLoop,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      pause.reason,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
    )
  }

  internal enum class AuditGapDriveAction { Continue, Stop }

  fun invalidateReviewGenerationIfNeeded(runLoop: FeatureTaskRuntimeRunLoop) {
    if (
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in
      runLoop.state.phasesRequiringDurableGateInvalidation()
    ) {
      return
    }
    val generation = checkNotNull(
      runLoop.recorder.persistReviewGenerationInvalidation(runLoop.request.workflowId, runLoop.request.dbPathOverride),
    ) {
      "Could not durably invalidate legacy review evidence for workflow '${runLoop.request.workflowId}'."
    }
    runLoop.state.advanceReviewGeneration(generation)
    runLoop.state.resetInvalidatedReviewGeneration()
    if (runLoop.session.pendingReentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
      runLoop.session.pendingReentry = null
      runLoop.session.activeReentry = null
    }
  }

  fun loadMigratedAuditGapPause(runLoop: FeatureTaskRuntimeRunLoop): FeatureTaskRuntimeAuditGapPause? =
    runLoop.recorder.loadAuditGapPause(runLoop.request.workflowId, runLoop.request.dbPathOverride)?.let { pause ->
      if (pause.pauseKind != AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD) {
        pause
      } else {
        val migrated = pause.copy(operatorDecision = null, grantConsumed = true)
        runLoop.recorder.persistAuditGapPause(runLoop.request.workflowId, migrated, runLoop.request.dbPathOverride)
        runCatching {
          runLoop.diagnostics.warning(
            "Cleared a legacy audit-gap warning-threshold pause for workflow '${runLoop.request.workflowId}'; " +
              "warning thresholds are advisory.",
          )
        }
        migrated
      }
    }

  internal fun resolveAuditGapPauseDriveAction(
    runLoop: FeatureTaskRuntimeRunLoop,
    auditGapPause: FeatureTaskRuntimeAuditGapPause,
  ): AuditGapDriveAction {
    when (auditGapPause.operatorDecision) {
      AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK -> {
        runLoop.collaborators.driveContinued1.abandonAuditGapSubtask(runLoop, auditGapPause)
        return AuditGapDriveAction.Stop
      }
      AUDIT_GAP_PAUSE_DECISION_RETRY_FIX -> {
        if (!auditGapPause.grantConsumed) {
          runLoop.session.auditGapRetryResumePending = true
        }
        return AuditGapDriveAction.Continue
      }
      else -> {
        if (runLoop.session.pendingReentry == null && !auditGapPause.grantConsumed) {
          reSurfaceAuditGapPause(runLoop, auditGapPause)
          return AuditGapDriveAction.Stop
        }
      }
    }
    return AuditGapDriveAction.Continue
  }

  fun validateAuditGapResumeOrBlock(runLoop: FeatureTaskRuntimeRunLoop): Boolean {
    val resumedReentry = runLoop.session.pendingReentry
    if (
      resumedReentry?.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID ||
      resumedReentry.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    ) {
      return true
    }
    val reason = runLoop.state.auditGapPlanningContextError() ?: return true
    runLoop.collaborators.backwardEdge.blockInvalidAuditGapRecovery(runLoop, resumedReentry, reason)
    return false
  }

  fun runPhaseDriveLoop(runLoop: FeatureTaskRuntimeRunLoop) {
    var phaseId: String? = runLoop.session.pendingReentry?.phaseId ?: runLoop.transitions.forwardPhaseIds.first()
    while (phaseId != null) {
      val settled = runLoop.advance(phaseId)
      val completedPhaseId = settled.completedPhaseId
      phaseId = if (completedPhaseId != null) {
        runLoop.collaborators.driveContinued2.nextPhaseAfter(
          runLoop,
          completedPhaseId,
          requireNotNull(settled.completedVerdict),
        )
      } else {
        null
      }
    }
  }
}
