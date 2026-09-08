package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics

interface FeatureTaskRuntimeImplementationAttemptValidator {
  @OpenBoundaryMap("Feature-task-runtime implementation-attempt wire map at the schema-validation seam")
  fun validateImplementationAttemptRecord(attemptRecord: Map<String, Any?>, sourceLabel: String)
}

object NoopFeatureTaskRuntimeImplementationAttemptValidator : FeatureTaskRuntimeImplementationAttemptValidator {
  override fun validateImplementationAttemptRecord(attemptRecord: Map<String, Any?>, sourceLabel: String) {
    RecordingNullObjectDiagnostics.recordSwallow(
      "NoopFeatureTaskRuntimeImplementationAttemptValidator",
      "validateImplementationAttemptRecord(sourceLabel=$sourceLabel)",
    )
  }
}
