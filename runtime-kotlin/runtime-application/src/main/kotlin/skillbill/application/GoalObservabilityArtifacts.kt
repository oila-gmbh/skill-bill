package skillbill.application

import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.model.GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.model.GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilityEvent
import skillbill.workflow.model.goalObservabilityHistoryFromArtifacts

internal object GoalObservabilityArtifacts {
  internal data class GoalObservabilityWorktreeActivity(
    val changedFileSummary: GoalObservabilityChangedFileSummary?,
    val diffStat: GoalObservabilityDiffStat?,
  )

  internal data class ProgressInput(
    val artifacts: Map<String, Any?>,
    val workflowId: String,
    val workflowStatus: String,
    val currentStepId: String,
    val worktreeActivity: GoalObservabilityWorktreeActivity? = null,
  )

  private data class RequiredProgressFields(
    val progressEvent: Map<*, *>,
    val issueKey: String,
    val subtaskId: Int,
    val timestamp: String,
  )

  fun patchForProgressEvent(input: ProgressInput, validator: GoalObservabilityEventValidator): Map<String, Any?>? =
    eventFrom(input)?.let { event ->
      val eventMap = event.toArtifactMap()
      validator.validate(eventMap, GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY)
      val history = goalObservabilityHistoryFromArtifacts(input.artifacts, validator).append(event).toArtifactList()
      history.forEachIndexed { index, item ->
        validator.validate(item, "$GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY[$index]")
      }
      linkedMapOf(
        GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY to eventMap,
        GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY to history,
      )
    }

  private fun eventFrom(input: ProgressInput): GoalObservabilityEvent? {
    val fields = requiredProgressFields(input) ?: return null
    return eventFrom(input, fields.progressEvent, fields.issueKey, fields.subtaskId, fields.timestamp)
  }

  private fun requiredProgressFields(input: ProgressInput): RequiredProgressFields? {
    val progressEvent = input.artifacts["progress_event"] as? Map<*, *>
    val continuation = input.artifacts["goal_continuation"] as? Map<*, *>
    val issueKey = continuation?.get("issue_key")?.toString()?.takeIf(String::isNotBlank)
    val subtaskId = continuation?.get("subtask_id").asGoalObservabilityIntOrNull()
    val timestamp = progressEvent?.get("timestamp")?.toString()?.takeIf(String::isNotBlank)
    return when {
      progressEvent == null -> null
      issueKey == null -> null
      subtaskId == null -> null
      timestamp == null -> null
      else -> RequiredProgressFields(progressEvent, issueKey, subtaskId, timestamp)
    }
  }

  private fun eventFrom(
    input: ProgressInput,
    progressEvent: Map<*, *>,
    issueKey: String,
    subtaskId: Int,
    timestamp: String,
  ): GoalObservabilityEvent {
    val kind = progressEvent["kind"]?.toString()?.takeIf(String::isNotBlank) ?: "durable_progress"
    return GoalObservabilityEvent(
      issueKey = issueKey,
      subtaskId = subtaskId,
      workflowId = input.workflowId,
      workflowPhase = progressEvent["step_id"]?.toString()?.takeIf(String::isNotBlank)
        ?: input.currentStepId.takeIf(String::isNotBlank)
        ?: "unknown",
      workerRole = progressEvent["source"]?.toString()?.takeIf(String::isNotBlank) ?: "unknown",
      livenessClass = kind,
      activitySummary = progressEvent["message"]?.toString()?.takeIf(String::isNotBlank)
        ?: "workflow_status=${input.workflowStatus}; progress_kind=$kind",
      timestamp = timestamp,
      sequenceNumber = progressEvent["sequence"].asGoalObservabilityIntOrNull() ?: 0,
      changedFileSummary = input.worktreeActivity?.changedFileSummary,
      diffStat = input.worktreeActivity?.diffStat,
    )
  }

  private fun Any?.asGoalObservabilityIntOrNull(): Int? = when (this) {
    is Int -> this
    is Number -> toInt()
    is String -> toIntOrNull()
    else -> null
  }
}
