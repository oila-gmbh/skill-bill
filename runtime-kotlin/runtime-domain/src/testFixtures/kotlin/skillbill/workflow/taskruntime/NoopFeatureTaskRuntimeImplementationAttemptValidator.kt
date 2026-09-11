package skillbill.workflow.taskruntime

object NoopFeatureTaskRuntimeImplementationAttemptValidator : FeatureTaskRuntimeImplementationAttemptValidator {
  override fun validateImplementationAttemptRecord(attemptRecord: Map<String, Any?>, sourceLabel: String) {
  }
}
