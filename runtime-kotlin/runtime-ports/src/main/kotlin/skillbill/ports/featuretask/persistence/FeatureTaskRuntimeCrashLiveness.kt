package skillbill.ports.featuretask.persistence

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection

object FeatureTaskRuntimeCrashLiveness {
  fun isConfirmedDead(inspection: FeatureTaskRuntimeProcessInspection): Boolean = when (inspection) {
    FeatureTaskRuntimeProcessInspection.NotRunning -> true
    FeatureTaskRuntimeProcessInspection.ExactLive -> false
    is FeatureTaskRuntimeProcessInspection.OwnershipMismatch -> false
    is FeatureTaskRuntimeProcessInspection.Unsupported -> false
  }
}
