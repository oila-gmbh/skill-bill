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

/**
 * Default supervisor for seams that do not perform process liveness (tests, artifact-only stores).
 * Every inspection is [FeatureTaskRuntimeProcessInspection.Unsupported] — ambiguous evidence that is
 * never confirmed dead — so a seam wired with this default never reconciles.
 */
object NoopFeatureTaskRuntimeWorkerSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("noop-host", "noop-boot", 1, "noop-birth")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
    FeatureTaskRuntimeProcessInspection.Unsupported("no-op supervisor performs no liveness inspection")

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun startHeartbeat(intervalSeconds: Long, heartbeat: () -> Unit): FeatureTaskRuntimeHeartbeat =
    FeatureTaskRuntimeHeartbeat {}

  override fun pause(durationMillis: Long) = Unit
}
