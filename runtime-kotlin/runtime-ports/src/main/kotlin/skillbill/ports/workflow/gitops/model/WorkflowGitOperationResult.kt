package skillbill.ports.workflow.gitops.model

sealed interface WorkflowGitOperationResult {
  val value: String
  val error: String
  val wireValue: String

  data class Ok(
    override val value: String = "",
    override val error: String = "",
  ) : WorkflowGitOperationResult {
    override val wireValue: String = "ok"
  }

  data class Failed(
    override val error: String = "",
    override val value: String = "",
  ) : WorkflowGitOperationResult {
    override val wireValue: String = "error"
  }

  companion object {
    fun fromWire(status: String, value: String = "", error: String = ""): WorkflowGitOperationResult =
      when (status) {
        "ok" -> Ok(value = value, error = error)
        "error" -> Failed(error = error, value = value)
        else -> Failed(error = error.ifBlank { status }, value = value)
      }
  }
}

fun WorkflowGitOperationResult.recordsNothingToCommit(): Boolean {
  val text = "$error $value"
  return NOTHING_TO_COMMIT_MARKERS.any { marker -> marker in text }
}

private val NOTHING_TO_COMMIT_MARKERS = listOf(
  "no changes added to commit",
  "nothing to commit",
  "nothing added to commit",
)
