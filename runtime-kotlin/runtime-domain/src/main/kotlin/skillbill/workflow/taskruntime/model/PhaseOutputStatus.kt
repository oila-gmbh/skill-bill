package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.engine.model.WorkflowWireTokens

enum class PhaseOutputStatus(val wireValue: String) {
  COMPLETED(WorkflowWireTokens.COMPLETED),
  BLOCKED(WorkflowWireTokens.BLOCKED),
  FAILED(WorkflowWireTokens.FAILED),
  ;

  companion object {
    fun fromWire(value: String): PhaseOutputStatus = when (value) {
      WorkflowWireTokens.COMPLETED, WorkflowWireTokens.COMPLETE -> COMPLETED
      WorkflowWireTokens.BLOCKED, "block" -> BLOCKED
      WorkflowWireTokens.FAILED, "fail" -> FAILED
      else -> throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = "phase_output",
        reason = "status must be completed, blocked, or failed",
      )
    }
  }
}
