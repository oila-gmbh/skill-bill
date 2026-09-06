package skillbill.workflow.taskruntime

object NoopFeatureTaskRuntimeQuarantineValidator : FeatureTaskRuntimeQuarantineValidator {
  override fun validateQuarantineRecord(quarantineRecord: Map<String, Any?>, sourceLabel: String) {
  }
}
