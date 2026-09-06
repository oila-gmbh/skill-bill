package skillbill.ports.workflow.gitops.model

sealed interface WorkflowGitOperationResult {
  val value: String
  val error: String
  val ok: Boolean
    get() = this is WorkflowGitOperationResult.Ok

  fun recordsNothingToCommit(): Boolean {
    val text = "$error $value"
    return NOTHING_TO_COMMIT_MARKERS.any { marker -> marker in text }
  }

  data class Ok(override val value: String = "") : WorkflowGitOperationResult {
    override val error: String = ""
  }

  data class Failed(
    override val error: String,
    override val value: String = "",
  ) : WorkflowGitOperationResult

  companion object {
    operator fun invoke(status: String, value: String = "", error: String = ""): WorkflowGitOperationResult =
      if (status == OK_STATUS) Ok(value) else Failed(error.ifBlank { status }, value)
  }
}

private const val OK_STATUS = "ok"

private val NOTHING_TO_COMMIT_MARKERS = listOf(
  "no changes added to commit",
  "nothing to commit",
  "nothing added to commit",
)
