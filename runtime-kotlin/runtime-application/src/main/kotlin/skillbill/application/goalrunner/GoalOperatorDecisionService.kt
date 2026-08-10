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
      ?: return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "No prepared goal exists for '${request.issueKey}'.",
      )
    val subtask = loaded.manifest.subtasks.firstOrNull { it.id == request.subtaskId }
      ?: return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "Subtask ${request.subtaskId} is not part of this goal.",
      )
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
      ?: return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "Subtask ${request.subtaskId} has no child workflow to record an operator decision against.",
      )
    val reviewState = goalContinuationRecorder.reviewState(workflowId, request.dbPathOverride)
      ?: return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "Subtask ${request.subtaskId} has no durable review state for an operator decision.",
      )
    if (!reviewState.acceptsOperatorDecision) {
      return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "Subtask ${request.subtaskId} does not accept an operator decision; it carries no unresolved " +
          "Blocker or Major.",
      )
    }
    val updated = try {
      goalContinuationRecorder.updateReviewState(workflowId, request.dbPathOverride) { state ->
        state.applyOperatorDecision(request.decision)
      }
    } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
      return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        error.message.orEmpty().ifBlank { "The operator decision was rejected by review-state validation." },
      )
    }
    if (updated == null) {
      return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "The operator decision could not be persisted onto the durable review state.",
      )
    }
    return GoalRunnerOperatorDecisionResult.Recorded(
      issueKey = request.issueKey,
      parentWorkflowId = loaded.parentWorkflowId,
      subtaskId = request.subtaskId,
      workflowId = workflowId,
      decision = request.decision.wireValue,
    )
  }
}
