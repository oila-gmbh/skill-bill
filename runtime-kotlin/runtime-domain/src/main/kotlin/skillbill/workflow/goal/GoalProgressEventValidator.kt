package skillbill.workflow.goal

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics

interface GoalProgressEventValidator {
  @OpenBoundaryMap("Goal progress event wire map at the schema-validation seam")
  fun validate(event: Map<String, Any?>, sourceLabel: String)
}

object NoopGoalProgressEventValidator : GoalProgressEventValidator {
  override fun validate(event: Map<String, Any?>, sourceLabel: String) {
    RecordingNullObjectDiagnostics.recordSwallow("NoopGoalProgressEventValidator", "validate(sourceLabel=$sourceLabel)")
  }
}
