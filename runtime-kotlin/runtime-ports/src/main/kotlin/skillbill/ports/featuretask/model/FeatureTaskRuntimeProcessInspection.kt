package skillbill.ports.featuretask.model

sealed interface FeatureTaskRuntimeProcessInspection {
  data object ExactLive : FeatureTaskRuntimeProcessInspection
  data object NotRunning : FeatureTaskRuntimeProcessInspection
  data class OwnershipMismatch(val reason: String) : FeatureTaskRuntimeProcessInspection
  data class Unsupported(val reason: String) : FeatureTaskRuntimeProcessInspection
}
