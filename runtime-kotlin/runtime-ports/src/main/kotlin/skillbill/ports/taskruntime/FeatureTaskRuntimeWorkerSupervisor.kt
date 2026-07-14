package skillbill.ports.taskruntime

import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection

interface FeatureTaskRuntimeWorkerSupervisor {
  fun currentProcess(): FeatureTaskRuntimeProcessIdentity

  fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection

  fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean

  fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean

  fun startHeartbeat(intervalSeconds: Long, heartbeat: () -> Unit): FeatureTaskRuntimeHeartbeat

  fun pause(durationMillis: Long)
}

fun interface FeatureTaskRuntimeHeartbeat {
  fun stop()
}
