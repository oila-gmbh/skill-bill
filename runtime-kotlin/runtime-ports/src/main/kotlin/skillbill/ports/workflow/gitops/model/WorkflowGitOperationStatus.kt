package skillbill.ports.workflow.gitops.model

enum class WorkflowGitOperationStatus(val wireValue: String) {
  OK("ok"),
  ERROR("error");

  companion object {
    fun fromWire(value: String): WorkflowGitOperationStatus? = entries.firstOrNull { it.wireValue == value }
  }
}
