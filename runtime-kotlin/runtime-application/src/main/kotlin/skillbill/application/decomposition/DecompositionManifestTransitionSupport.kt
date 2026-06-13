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
