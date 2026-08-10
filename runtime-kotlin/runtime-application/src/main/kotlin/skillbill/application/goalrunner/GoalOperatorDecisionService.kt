package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.application.model.GoalRunnerOperatorDecisionRequest
import skillbill.application.model.GoalRunnerOperatorDecisionResult
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.goalrunner.GoalRunnerManifestStore

/**
 * Records an out-of-band operator decision on a paused goal child. Persists onto durable review
 * state only — never edits `decomposition-manifest.yaml`. Resume consumes the decision.
 */
@Inject
class GoalOperatorDecisionService(
  private val manifestStore: GoalRunnerManifestStore,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
) {
  fun record(request: GoalRunnerOperatorDecisionRequest): GoalRunnerOperatorDecisionResult {
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
    val subtask = loaded?.manifest?.subtasks?.firstOrNull { it.id == request.subtaskId }
    val workflowId = subtask?.workflowId?.takeIf(String::isNotBlank)
    val reviewState = workflowId?.let { id ->
      goalContinuationRecorder.reviewState(id, request.dbPathOverride)
    }
    val rejectReason = when {
      loaded == null ->
        "No prepared goal exists for '${request.issueKey}'."
      subtask == null ->
        "Subtask ${request.subtaskId} is not part of this goal."
      workflowId == null ->
        "Subtask ${request.subtaskId} has no child workflow to record an operator decision against."
      reviewState == null ->
        "Subtask ${request.subtaskId} has no durable review state for an operator decision."
      !reviewState.acceptsOperatorDecision ->
        "Subtask ${request.subtaskId} does not accept an operator decision; it carries no unresolved " +
          "Blocker or Major."
      else -> null
    }
    if (rejectReason != null) {
      return GoalRunnerOperatorDecisionResult.Rejected(request.issueKey, rejectReason)
    }
    val parentWorkflowId = requireNotNull(loaded).parentWorkflowId
    val childWorkflowId = requireNotNull(workflowId)
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
      GoalRunnerOperatorDecisionResult.Recorded(
        issueKey = request.issueKey,
        parentWorkflowId = parentWorkflowId,
        subtaskId = request.subtaskId,
        workflowId = childWorkflowId,
        decision = request.decision.wireValue,
      )
    }
  }
}
