package skillbill.workflow.taskruntime

object NoopFeatureTaskRuntimePlanningProjectionValidator : FeatureTaskRuntimePlanningProjectionValidator {
  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String) {
  }
}
