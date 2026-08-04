package skillbill.ports.taskruntime

import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection

interface FeatureTaskRuntimeWorkerSupervisor {
  fun currentProcess(): FeatureTaskRuntimeProcessIdentity

  fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection

  fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean

  fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean

  fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat

  fun pause(durationMillis: Long)
}

interface FeatureTaskRuntimeHeartbeat {
  fun stop()

  /**
   * The reason renewal ended because another owner holds the lease, or null while this process is
   * still the fenced owner. The run owner must consult this before reporting its phase successful:
   * only lease operations are owner-token fenced, so a de-fenced process that kept running would go
   * on writing workflow state alongside the owner that displaced it.
   */
  fun fencingLostReason(): String?
}

object NoopFeatureTaskRuntimeHeartbeat : FeatureTaskRuntimeHeartbeat {
  override fun stop() = Unit

  override fun fencingLostReason(): String? = null
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

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat = NoopFeatureTaskRuntimeHeartbeat

  override fun pause(durationMillis: Long) = Unit
}
