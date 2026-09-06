package skillbill.ports.workflow.gitops.model

data class WorkflowScopedPathContent(
  val headPath: String,
  val basePath: String?,
  val headContent: String?,
  val baseContent: String?,
)

sealed interface WorkflowScopedPathContentsResult {
  val pairs: List<WorkflowScopedPathContent>
  val error: String
  val ok: Boolean
    get() = this is WorkflowScopedPathContentsResult.Ok

  data class Ok(override val pairs: List<WorkflowScopedPathContent> = emptyList()) :
    WorkflowScopedPathContentsResult {
    override val error: String = ""
  }

  data class Failed(
    override val error: String,
    override val pairs: List<WorkflowScopedPathContent> = emptyList(),
  ) : WorkflowScopedPathContentsResult

  companion object {
    operator fun invoke(
      status: String,
      pairs: List<WorkflowScopedPathContent> = emptyList(),
      error: String = "",
    ): WorkflowScopedPathContentsResult =
      if (status == OK_STATUS) Ok(pairs) else Failed(error.ifBlank { status }, pairs)
  }
}

private const val OK_STATUS = "ok"
