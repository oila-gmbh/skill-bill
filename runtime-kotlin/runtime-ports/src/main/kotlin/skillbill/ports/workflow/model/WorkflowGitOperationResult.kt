package skillbill.ports.workflow.model

data class WorkflowGitOperationResult(
  val status: String,
  val value: String = "",
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"
}
