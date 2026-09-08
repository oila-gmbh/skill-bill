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

/**
 * Outcome of one heartbeat tick. A tick that throws or fails transiently — a contended database being
 * the common case — must never end lease renewal: the lease is the only liveness evidence status and
 * `goal watch` have, so a dropped renewal makes a healthy worker read as idle for the rest of its phase.
 * Only [FencingLost], which is proof another owner holds the lease, deliberately ends renewal.
 */
sealed interface FeatureTaskRuntimeHeartbeatTick {
  data object Renewed : FeatureTaskRuntimeHeartbeatTick
  data class FencingLost(val reason: String) : FeatureTaskRuntimeHeartbeatTick
}

/**
 * Renewal cadence for one lease. [retryDelaySeconds] is deliberately shorter than
 * [intervalSeconds]: a contended attempt can burn the database busy timeout before failing, so
 * waiting a full interval afterwards would leave a lease with margin for only a single failed tick.
 * Escalation compares elapsed time since the last renewal against [leaseSeconds].
 */
data class FeatureTaskRuntimeHeartbeatPlan(
  val label: String,
  val intervalSeconds: Long,
  val leaseSeconds: Long,
  val retryDelaySeconds: Long = 1,
)
