package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics

interface FeatureTaskRuntimeQuarantineValidator {
  @OpenBoundaryMap("Feature-task-runtime quarantine record wire map at the schema-validation seam")
  fun validateQuarantineRecord(quarantineRecord: Map<String, Any?>, sourceLabel: String)
}

object NoopFeatureTaskRuntimeQuarantineValidator : FeatureTaskRuntimeQuarantineValidator {
  override fun validateQuarantineRecord(quarantineRecord: Map<String, Any?>, sourceLabel: String) {
    RecordingNullObjectDiagnostics.recordSwallow(
      "NoopFeatureTaskRuntimeQuarantineValidator",
      "validateQuarantineRecord(sourceLabel=$sourceLabel)",
    )
  }
}
