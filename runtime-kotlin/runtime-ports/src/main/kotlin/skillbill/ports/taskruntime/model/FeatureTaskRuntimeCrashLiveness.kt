package skillbill.ports.taskruntime.model

object FeatureTaskRuntimeCrashLiveness {
  fun isConfirmedDead(inspection: FeatureTaskRuntimeProcessInspection): Boolean = when (inspection) {
    FeatureTaskRuntimeProcessInspection.NotRunning -> true
    FeatureTaskRuntimeProcessInspection.ExactLive -> false
    is FeatureTaskRuntimeProcessInspection.OwnershipMismatch -> false
    is FeatureTaskRuntimeProcessInspection.Unsupported -> false
  }
}
