package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.model.GoalRunnerOperatorDecisionRequest
import skillbill.application.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision

@Inject
class GoalOperatorDecisionService(
  private val manifestStore: GoalRunnerManifestStore,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) {
  @Suppress("ReturnCount")
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
    val auditGapPause = recorder.loadAuditGapPause(childWorkflowId, request.dbPathOverride)
    if (auditGapPause != null) {
      return recordAuditGapPauseDecision(request, parentWorkflowId, childWorkflowId, auditGapPause)
    }
    return GoalRunnerOperatorDecisionResult.Rejected(
      request.issueKey,
      "Operator decisions over review remediation are removed; " +
        "the run advances to validate after one implement_fix round.",
    )
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
