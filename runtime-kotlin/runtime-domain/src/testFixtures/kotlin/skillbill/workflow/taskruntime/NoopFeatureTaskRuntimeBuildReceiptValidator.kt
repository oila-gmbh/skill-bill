package skillbill.workflow.taskruntime

object NoopFeatureTaskRuntimeBuildReceiptValidator : FeatureTaskRuntimeBuildReceiptValidator {
  override fun validateBuildReceipt(buildReceipt: Map<String, Any?>, sourceLabel: String) {
  }
}
