package skillbill.ports.workflow.model

enum class GoalChildWorkflowDeletionScope(val deletableStatuses: List<String>) {
  TERMINAL_ONLY(listOf("blocked", "failed", "abandoned", "completed")),
  TERMINAL_OR_RESUMABLE(listOf("blocked", "failed", "abandoned", "completed", "pending", "paused")),
}
