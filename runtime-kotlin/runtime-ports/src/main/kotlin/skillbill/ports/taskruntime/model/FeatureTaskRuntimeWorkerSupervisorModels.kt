package skillbill.ports.taskruntime.model

data class FeatureTaskRuntimeProcessIdentity(
  val hostIdentity: String,
  val bootIdentity: String,
  val pid: Long,
  val processBirthToken: String,
)

sealed interface FeatureTaskRuntimeProcessInspection {
  data object ExactLive : FeatureTaskRuntimeProcessInspection
  data object NotRunning : FeatureTaskRuntimeProcessInspection
  data class OwnershipMismatch(val reason: String) : FeatureTaskRuntimeProcessInspection
  data class Unsupported(val reason: String) : FeatureTaskRuntimeProcessInspection
}
