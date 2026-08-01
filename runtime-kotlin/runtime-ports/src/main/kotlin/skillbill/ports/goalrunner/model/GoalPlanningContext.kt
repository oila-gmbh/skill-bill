package skillbill.ports.goalrunner.model

/**
 * Child-only repository context. The parent goal projection does not retain this payload; it keeps
 * manifest metadata, the current subtask index, and terminal outcomes only.
 */
data class GoalPlanningContext(
  val platformPacks: Map<String, String>,
  val boundaryMemory: Map<String, String>,
  val validationGuidance: String,
) {
  companion object {
    const val MAX_DISCOVERY_FILE_COUNT = 32
    const val MAX_DISCOVERY_EXCERPT_BYTES = 4_096
    const val MAX_DISCOVERY_TOTAL_BYTES = 32 * 1_024L
  }
}
