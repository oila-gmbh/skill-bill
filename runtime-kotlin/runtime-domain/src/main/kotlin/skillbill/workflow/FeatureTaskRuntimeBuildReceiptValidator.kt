package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap

interface FeatureTaskRuntimeBuildReceiptValidator {
  @OpenBoundaryMap("Feature-task-runtime build receipt wire map at the schema-validation seam")
  fun validateBuildReceipt(buildReceipt: Map<String, Any?>, sourceLabel: String)
}

object NoopFeatureTaskRuntimeBuildReceiptValidator : FeatureTaskRuntimeBuildReceiptValidator {
  override fun validateBuildReceipt(buildReceipt: Map<String, Any?>, sourceLabel: String) = Unit
}
