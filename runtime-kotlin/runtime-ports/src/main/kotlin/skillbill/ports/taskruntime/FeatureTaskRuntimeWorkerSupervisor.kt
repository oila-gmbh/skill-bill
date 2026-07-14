package skillbill.ports.taskruntime

import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership

interface FeatureTaskRuntimeWorkerSupervisor {
  fun currentProcess(): FeatureTaskRuntimeProcessIdentity

  fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection

  fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean

  fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean
}

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
