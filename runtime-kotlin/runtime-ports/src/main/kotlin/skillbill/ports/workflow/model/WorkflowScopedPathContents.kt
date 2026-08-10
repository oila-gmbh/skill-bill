package skillbill.ports.workflow.model

/**
 * One logical file in the validate-boundary inventory, paired with its base-ref
 * identity (rename-aware) and contents at HEAD (current tree) and base.
 */
data class WorkflowScopedPathContent(
  val headPath: String,
  val basePath: String?,
  val headContent: String?,
  val baseContent: String?,
)

data class WorkflowScopedPathContentsResult(
  val status: String,
  val pairs: List<WorkflowScopedPathContent> = emptyList(),
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"
}
