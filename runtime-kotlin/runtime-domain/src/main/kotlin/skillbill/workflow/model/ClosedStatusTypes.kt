package skillbill.workflow.model

enum class DecompositionStatus(val wireValue: String) {
  PENDING("pending"),
  IN_PROGRESS("in_progress"),
  COMPLETE("complete"),
  SKIPPED("skipped"),
  BLOCKED("blocked"),
  ;

  companion object {
    fun fromWire(value: String): DecompositionStatus? = when (value) {
      "completed" -> COMPLETE
      else -> entries.firstOrNull { it.wireValue == value }
    }
  }
}

enum class WorkflowStatus(val wireValue: String) {
  PENDING("pending"),
  RUNNING("running"),
  COMPLETED("completed"),
  FAILED("failed"),
  ABANDONED("abandoned"),
  BLOCKED("blocked"),
  PAUSED("paused"),
  TIMED_OUT("timed_out"),
  ;

  companion object {
    fun fromWire(value: String): WorkflowStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class WorkflowStepStatus(val wireValue: String) {
  PENDING("pending"),
  RUNNING("running"),
  COMPLETED("completed"),
  FAILED("failed"),
  BLOCKED("blocked"),
  SKIPPED("skipped"),
  PAUSED("paused"),
  ;

  companion object {
    fun fromWire(value: String): WorkflowStepStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

fun String?.decompositionStatus(): DecompositionStatus? = this?.let(DecompositionStatus::fromWire)

fun String?.workflowStatus(): WorkflowStatus? = this?.let(WorkflowStatus::fromWire)

fun Any?.workflowStepStatus(): WorkflowStepStatus? = (this as? String)?.let(WorkflowStepStatus::fromWire)

fun WorkflowStepStatus.workflowStepStatus(): WorkflowStepStatus = this
