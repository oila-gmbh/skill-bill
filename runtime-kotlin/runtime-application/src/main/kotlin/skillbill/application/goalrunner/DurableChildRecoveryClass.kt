package skillbill.application.goalrunner

import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress

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

/**
 * Recovery for a child holding planning bytes its parent has since replaced. Scoped reset refuses a
 * `pending` or `paused` child — it is resumable, and resuming is normally right — but resuming this
 * one re-imports the stale bytes and blocks again. Scoped replan is the command that both regenerates
 * the subtask's plan and drops the stale child, so it is what a planning-import conflict advertises.
 */
internal fun staleChildPlanningRecoveryCommand(issueKey: String, subtaskId: Int): String =
  "skill-bill goal replan $issueKey --subtask $subtaskId"
