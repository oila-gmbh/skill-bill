package skillbill.application.decomposition

import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest

internal fun DecompositionManifest.withBlockedSubtask(
  subtaskId: Int,
  reason: String,
  lastResumableStep: String,
): DecompositionManifest = copy(
  status = "blocked",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "blocked",
        blockedReason = reason.ifBlank { "Subtask $subtaskId is blocked." },
        lastResumableStep = lastResumableStep,
      )
    } else {
      subtask
    }
  },
)

internal fun DecompositionManifest.withRetriedSubtask(
  subtaskId: Int,
  workflowId: String,
  lastResumableStep: String,
): DecompositionManifest {
  require(subtasks.any { it.id == subtaskId }) {
    "Cannot retry unknown decomposition subtask '$subtaskId'."
  }
  return copy(
    status = "in_progress",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
    subtasks = subtasks.map { subtask ->
      if (subtask.id == subtaskId) {
        subtask.copy(
          status = "in_progress",
          workflowId = workflowId,
          blockedReason = null,
          lastResumableStep = lastResumableStep,
        )
      } else {
        subtask
      }
    },
  )
}
