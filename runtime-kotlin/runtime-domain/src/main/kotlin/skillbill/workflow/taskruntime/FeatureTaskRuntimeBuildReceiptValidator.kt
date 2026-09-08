package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics

interface FeatureTaskRuntimeBuildReceiptValidator {
  @OpenBoundaryMap("Feature-task-runtime build receipt wire map at the schema-validation seam")
  fun validateBuildReceipt(buildReceipt: Map<String, Any?>, sourceLabel: String)
}

object NoopFeatureTaskRuntimeBuildReceiptValidator : FeatureTaskRuntimeBuildReceiptValidator {
  override fun validateBuildReceipt(buildReceipt: Map<String, Any?>, sourceLabel: String) {
    RecordingNullObjectDiagnostics.recordSwallow(
      "NoopFeatureTaskRuntimeBuildReceiptValidator",
      "validateBuildReceipt(sourceLabel=$sourceLabel)",
    )
  }
}
