package skillbill.ports.workflow.model

data class WorkflowGitOperationResult(
  val status: String,
  val value: String = "",
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"

  fun recordsNothingToCommit(): Boolean {
    val text = "$error $value"
    return NOTHING_TO_COMMIT_MARKERS.any { marker -> marker in text }
  }
}

private val NOTHING_TO_COMMIT_MARKERS = listOf(
  "no changes added to commit",
  "nothing to commit",
  "nothing added to commit",
)
