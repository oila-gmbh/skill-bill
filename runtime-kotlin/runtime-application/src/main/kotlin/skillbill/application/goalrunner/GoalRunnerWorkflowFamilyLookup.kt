package skillbill.application.goalrunner

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.contracts.JsonCodec
import skillbill.error.LegacyProseWorkflowError
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode

fun workflowFamilyFor(workflowStates: WorkflowStateRepository, workflowId: String): WorkflowFamily? {
  val featureTaskRow = workflowStates.getFeatureTaskWorkflow(workflowId)
  if (featureTaskRow != null) {
    return when (featureTaskRow.mode) {
      FeatureTaskWorkflowMode.RUNTIME -> WorkflowFamily.TASK_RUNTIME
      FeatureTaskWorkflowMode.PROSE, null -> throw LegacyProseWorkflowError(workflowId, featureTaskRow.issueKey)
    }
  }
  return if (workflowStates.getFeatureVerifyWorkflow(workflowId) != null) {
    WorkflowFamily.VERIFY
  } else {
    null
  }
}

fun GoalRunnerControlState.pauseAtOperatorBoundary(
  pausedAtNow: String,
  targetReached: Boolean = false,
): GoalRunnerControlState = when {
  // Already paused: the original pause instant is the one that matters, so it is never restamped.
  paused -> copy(stopAfterConsumed = stopAfterConsumed || targetReached)
  pauseRequested -> copy(
    pauseConsumed = true,
    paused = true,
    pauseReason = pauseReason ?: GOAL_PAUSE_REASON_OPERATOR_REQUEST,
    pausedAt = pausedAtNow,
    stopAfterConsumed = stopAfterConsumed || targetReached,
  )
  targetReached -> copy(
    paused = true,
    pauseReason = GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK,
    pausedAt = pausedAtNow,
    stopAfterConsumed = true,
  )
  else -> this
}

fun decodeGoalAgentAddonSelection(raw: Any?): AgentAddonSelection {
  val values = raw ?: return AgentAddonSelection()
  val entries = values as? List<*> ?: error("Goal review policy agent_addon_selection must be a list.")
  return AgentAddonSelection(
    entries.mapIndexed { index, value ->
      val entry = JsonCodec.anyToStringAnyMap(value)
        ?: error("Goal review policy agent_addon_selection entry $index must be a map.")
      check(entry.keys == setOf("slug", "source_identity", "content_sha256")) {
        "Goal review policy agent_addon_selection entry $index has invalid fields."
      }
      PersistedAgentAddonSelectionEntry(
        entry["slug"] as? String ?: error("Goal review policy add-on entry $index is missing slug."),
        entry["source_identity"] as? String
          ?: error("Goal review policy add-on entry $index is missing source_identity."),
        entry["content_sha256"] as? String
          ?: error("Goal review policy add-on entry $index is missing content_sha256."),
      )
    },
  )
}
