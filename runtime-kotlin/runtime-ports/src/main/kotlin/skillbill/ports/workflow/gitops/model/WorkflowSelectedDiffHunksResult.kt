package skillbill.ports.workflow.gitops.model

import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks

data class WorkflowSelectedDiffHunksRequest(
  val paths: List<String>,
  val includeStaged: Boolean = true,
  val includeUnstaged: Boolean = true,
  val maxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val maxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val maxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
) {
  init {
    require(paths.all { it.isNotBlank() }) { "selected diff paths must not be blank." }
    require(maxHunks > 0) { "maxHunks must be positive." }
    require(maxLines > 0) { "maxLines must be positive." }
    require(maxBytes > 0) { "maxBytes must be positive." }
  }
}

sealed interface WorkflowSelectedDiffHunksResult {
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks
  val error: String
  val ok: Boolean
    get() = this is WorkflowSelectedDiffHunksResult.Ok

  data class Ok(
    override val selectedDiffHunks: GoalObservabilitySelectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  ) : WorkflowSelectedDiffHunksResult {
    override val error: String = ""
  }

  data class Failed(
    override val error: String,
    override val selectedDiffHunks: GoalObservabilitySelectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  ) : WorkflowSelectedDiffHunksResult

  companion object {
    operator fun invoke(
      status: String,
      selectedDiffHunks: GoalObservabilitySelectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
      error: String = "",
    ): WorkflowSelectedDiffHunksResult =
      if (status == OK_STATUS) Ok(selectedDiffHunks) else Failed(error.ifBlank { status }, selectedDiffHunks)
  }
}

private const val OK_STATUS = "ok"

const val DEFAULT_SELECTED_DIFF_MAX_HUNKS: Int = 6
const val DEFAULT_SELECTED_DIFF_MAX_LINES: Int = 120
const val DEFAULT_SELECTED_DIFF_MAX_BYTES: Int = 12_000
