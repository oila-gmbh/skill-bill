package skillbill.ports.persistence.model

/**
 * Which durable child statuses a scoped goal-child deletion may remove. `running` is absent from both
 * scopes: that child owns a live worker.
 */
enum class GoalChildWorkflowDeletionScope(val deletableStatuses: List<String>) {
  TERMINAL_ONLY(listOf("blocked", "failed", "abandoned", "completed")),
  TERMINAL_OR_RESUMABLE(listOf("blocked", "failed", "abandoned", "completed", "pending", "paused")),
}
