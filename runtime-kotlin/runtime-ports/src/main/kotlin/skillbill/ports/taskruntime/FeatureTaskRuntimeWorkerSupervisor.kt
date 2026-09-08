package skillbill.ports.taskruntime

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Duration

interface FeatureTaskRuntimeWorkerSupervisor {
  fun currentProcess(): FeatureTaskRuntimeProcessIdentity

  fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
    FeatureTaskRuntimeProcessInspection.Unsupported("supervisor does not implement process inspection")

  /**
   * Block until [ownership] is no longer [FeatureTaskRuntimeProcessInspection.ExactLive], or until
   * [timeout] elapses. Used by a second foreground `skill-bill goal` that raced the same launch.
   * On timeout the peer may still be live — the caller re-inspects and fails closed. A no-op default
   * keeps tests and artifact-only seams from waiting. Must not wait on the current process.
   */
  fun awaitExit(ownership: FeatureTaskRuntimeWorkerOwnership, timeout: Duration)

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
  private const val NAME = "NoopFeatureTaskRuntimeHeartbeat"

  override fun stop() {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "stop()")
  }

  override fun fencingLostReason(): String? {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "fencingLostReason()")
    return null
  }
}

/**
 * Default supervisor for seams that do not perform process liveness (tests, artifact-only stores).
 * Every inspection is [FeatureTaskRuntimeProcessInspection.Unsupported] — ambiguous evidence that is
 * never confirmed dead — so a seam wired with this default never reconciles.
 */
object NoopFeatureTaskRuntimeWorkerSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  private const val NAME = "NoopFeatureTaskRuntimeWorkerSupervisor"

  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "currentProcess()")
    return FeatureTaskRuntimeProcessIdentity("noop-host", "noop-boot", 1, "noop-birth")
  }

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "inspect(workflowId=${ownership.workflowId})")
    return FeatureTaskRuntimeProcessInspection.Unsupported("no-op supervisor performs no liveness inspection")
  }

  override fun awaitExit(ownership: FeatureTaskRuntimeWorkerOwnership, timeout: Duration) {
    RecordingNullObjectDiagnostics.recordSwallow(
      NAME,
      "awaitExit(workflowId=${ownership.workflowId}, timeout=$timeout)",
    )
  }

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "terminateGracefully(workflowId=${ownership.workflowId})")
    return false
  }

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "terminateForcibly(workflowId=${ownership.workflowId})")
    return false
  }

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "startHeartbeat(label=${plan.label})")
    return NoopFeatureTaskRuntimeHeartbeat
  }

  override fun pause(durationMillis: Long) {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "pause(durationMillis=$durationMillis)")
  }
}
