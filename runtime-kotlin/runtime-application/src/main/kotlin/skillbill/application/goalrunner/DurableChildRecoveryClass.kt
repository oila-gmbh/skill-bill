package skillbill.application.goalrunner

import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress

internal enum class DurableChildRecoveryClass(val wireValue: String) {
  ABSENT("absent"),
  ACTIVE("active"),
  RESUMABLE("resumable"),
  INCOMPATIBLE_TERMINAL("incompatible_terminal"),
}

internal fun classifyDurableChild(progress: GoalRunnerWorkflowProgress?): DurableChildRecoveryClass =
  when (progress?.workflowStatus) {
    null -> DurableChildRecoveryClass.ABSENT
    "running" -> DurableChildRecoveryClass.ACTIVE
    "pending", "paused" -> DurableChildRecoveryClass.RESUMABLE
    "blocked", "failed", "abandoned", "timed_out", "completed" ->
      DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL
    else -> DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL
  }

internal fun scopedChildRecoveryCommand(issueKey: String, subtaskId: Int): String =
  "skill-bill goal reset $issueKey --subtask $subtaskId --delete-child-workflow"
