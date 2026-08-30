package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.model.GoalRunnerOperatorDecisionRequest
import skillbill.application.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause

@Inject
class GoalOperatorDecisionService(
  private val manifestStore: GoalRunnerManifestStore,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) {
  fun record(request: GoalRunnerOperatorDecisionRequest): GoalRunnerOperatorDecisionResult {
    when (val resolved = resolveChildWorkflow(request)) {
      is ResolvedChildWorkflow.Rejected -> return resolved.result
      is ResolvedChildWorkflow.Ok -> {
        val auditGapPause = recorder.loadAuditGapPause(resolved.childWorkflowId, request.dbPathOverride)
        return if (auditGapPause != null) {
          recordAuditGapPauseDecision(request, resolved.parentWorkflowId, resolved.childWorkflowId, auditGapPause)
        } else {
          GoalRunnerOperatorDecisionResult.Rejected(
            request.issueKey,
            "Operator decisions over review remediation are removed; " +
              "the run advances to validate after one implement_fix round.",
          )
        }
      }
    }
  }

  private fun resolveChildWorkflow(request: GoalRunnerOperatorDecisionRequest): ResolvedChildWorkflow? {
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
    return if (rejectReason != null) {
      ResolvedChildWorkflow.Rejected(GoalRunnerOperatorDecisionResult.Rejected(request.issueKey, rejectReason))
    } else {
      ResolvedChildWorkflow.Ok(
        parentWorkflowId = requireNotNull(loaded).parentWorkflowId,
        childWorkflowId = requireNotNull(workflowId),
      )
    }
  }

  private fun recordAuditGapPauseDecision(
    request: GoalRunnerOperatorDecisionRequest,
    parentWorkflowId: String,
    childWorkflowId: String,
    pause: FeatureTaskRuntimeAuditGapPause,
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

  private sealed class ResolvedChildWorkflow {
    data class Rejected(val result: GoalRunnerOperatorDecisionResult.Rejected) : ResolvedChildWorkflow()

    data class Ok(
      val parentWorkflowId: String,
      val childWorkflowId: String,
    ) : ResolvedChildWorkflow()
  }
}
