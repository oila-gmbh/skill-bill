package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.model.GoalRunnerOperatorDecisionRequest
import skillbill.application.model.GoalRunnerOperatorDecisionResult
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision

/**
 * Records an out-of-band operator decision on a paused goal child. A review-fix pause persists onto
 * durable review state; an audit-gap pause persists onto its durable pause artifact — both never edit
 * `decomposition-manifest.yaml`. Resume consumes the decision.
 */
@Inject
class GoalOperatorDecisionService(
  private val manifestStore: GoalRunnerManifestStore,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) {
  fun record(request: GoalRunnerOperatorDecisionRequest): GoalRunnerOperatorDecisionResult {
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
    val subtask = loaded?.manifest?.subtasks?.firstOrNull { it.id == request.subtaskId }
    val workflowId = subtask?.workflowId?.takeIf(String::isNotBlank)
    val rejectReason = when {
      loaded == null ->
        "No prepared goal exists for '${request.issueKey}'."
      subtask == null ->
        "Subtask ${request.subtaskId} is not part of this goal."
      workflowId == null ->
        "Subtask ${request.subtaskId} has no child workflow to record an operator decision against."
      else -> null
    }
    if (rejectReason != null) {
      return GoalRunnerOperatorDecisionResult.Rejected(request.issueKey, rejectReason)
    }
    val parentWorkflowId = requireNotNull(loaded).parentWorkflowId
    val childWorkflowId = requireNotNull(workflowId)
    // An audit-gap pause carries no review state: the decision is recorded on its durable pause
    // artifact, and an unmet acceptance criterion cannot be accepted-and-advanced.
    val auditGapPause = recorder.loadAuditGapPause(childWorkflowId, request.dbPathOverride)
    if (auditGapPause != null) {
      return recordAuditGapPauseDecision(request, parentWorkflowId, childWorkflowId, auditGapPause)
    }
    val reviewState = goalContinuationRecorder.reviewState(childWorkflowId, request.dbPathOverride)
    val reviewRejectReason = when {
      reviewState == null ->
        "Subtask ${request.subtaskId} has no durable review state for an operator decision."
      !reviewState.acceptsOperatorDecision ->
        "Subtask ${request.subtaskId} does not accept an operator decision; it carries no unresolved " +
          "Blocker or Major."
      else -> null
    }
    if (reviewRejectReason != null) {
      return GoalRunnerOperatorDecisionResult.Rejected(request.issueKey, reviewRejectReason)
    }
    val updated = try {
      goalContinuationRecorder.updateReviewState(childWorkflowId, request.dbPathOverride) { state ->
        state.applyOperatorDecision(request.decision)
      }
    } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
      return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        error.message.orEmpty().ifBlank { "The operator decision was rejected by review-state validation." },
      )
    }
    return if (updated == null) {
      GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "The operator decision could not be persisted onto the durable review state.",
      )
    } else {
      recordedResult(request, parentWorkflowId, childWorkflowId)
    }
  }

  private fun recordAuditGapPauseDecision(
    request: GoalRunnerOperatorDecisionRequest,
    parentWorkflowId: String,
    childWorkflowId: String,
    pause: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause,
  ): GoalRunnerOperatorDecisionResult {
    if (pause.grantConsumed) {
      return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "The audit-gap pause's retry grant is already consumed; a new operator decision is required to act.",
      )
    }
    return when (request.decision) {
      GoalSubtaskOperatorDecision.RETRY_FIX -> {
        recorder.persistAuditGapPause(
          childWorkflowId,
          pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
          request.dbPathOverride,
        )
        recordedResult(request, parentWorkflowId, childWorkflowId)
      }
      GoalSubtaskOperatorDecision.ABANDON_SUBTASK -> {
        recorder.persistAuditGapPause(
          childWorkflowId,
          pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK),
          request.dbPathOverride,
        )
        recordedResult(request, parentWorkflowId, childWorkflowId)
      }
      GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE -> GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "An unmet acceptance criterion cannot be accepted-and-advanced; choose retry_fix or " +
          "abandon_subtask for an audit-gap pause.",
      )
    }
  }

  private fun recordedResult(
    request: GoalRunnerOperatorDecisionRequest,
    parentWorkflowId: String,
    childWorkflowId: String,
  ): GoalRunnerOperatorDecisionResult = GoalRunnerOperatorDecisionResult.Recorded(
    issueKey = request.issueKey,
    parentWorkflowId = parentWorkflowId,
    subtaskId = request.subtaskId,
    workflowId = childWorkflowId,
    decision = request.decision.wireValue,
  )
}
