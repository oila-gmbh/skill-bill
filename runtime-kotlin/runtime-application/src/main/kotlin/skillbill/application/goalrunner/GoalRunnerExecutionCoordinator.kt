package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Clock
import java.time.Duration
import java.util.UUID

interface GoalRunnerExecutionCoordinator {
  fun <T> runOwned(parentWorkflowId: String, dbPathOverride: String?, block: () -> T): T

  companion object {
    val NONE: GoalRunnerExecutionCoordinator = object : GoalRunnerExecutionCoordinator {
      override fun <T> runOwned(parentWorkflowId: String, dbPathOverride: String?, block: () -> T): T = block()
    }
  }
}

class GoalRunnerExecutionAlreadyRunningException(parentWorkflowId: String, detail: String) : IllegalStateException(
  "Goal parent '$parentWorkflowId' cannot start: $detail",
)

/**
 * Adapt a goal execution lease into the worker ownership the supervisor inspects and terminates.
 * Shared by the execution coordinator's reclaim path and the stop verb so both judge liveness and
 * process identity by exactly the same evidence.
 */
internal fun GoalRunnerExecutionLease.asWorkerOwnership(parentWorkflowId: String) = FeatureTaskRuntimeWorkerOwnership(
  workflowId = parentWorkflowId,
  generation = generation,
  ownerToken = ownerToken,
  hostIdentity = hostIdentity,
  bootIdentity = bootIdentity,
  pid = pid,
  processBirthToken = processBirthToken,
  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  heartbeatAt = heartbeatAt,
  expiresAt = expiresAt,
  phaseId = "goal_runner",
  phaseAttempt = 1,
)

@Inject
class DefaultGoalRunnerExecutionCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock = Clock.systemUTC(),
) : GoalRunnerExecutionCoordinator {
  override fun <T> runOwned(parentWorkflowId: String, dbPathOverride: String?, block: () -> T): T {
    val existing = manifestStore.executionLease(parentWorkflowId, dbPathOverride)
    val expectedOwnerToken = existing?.let { reclaimableOwnerToken(parentWorkflowId, it) }
    val lease = newLease(existing, supervisor.currentProcess())
    if (!manifestStore.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken, dbPathOverride)) {
      throw GoalRunnerExecutionAlreadyRunningException(
        parentWorkflowId,
        "another goal runner claimed the execution lease before this run could start",
      )
    }
    val plan = FeatureTaskRuntimeHeartbeatPlan(
      label = parentWorkflowId,
      intervalSeconds = HEARTBEAT_SECONDS,
      leaseSeconds = LEASE_DURATION.seconds,
    )
    val heartbeat = supervisor.startHeartbeat(plan) {
      val now = clock.instant()
      val updated = lease.copy(heartbeatAt = now.toString(), expiresAt = now.plus(LEASE_DURATION).toString())
      if (manifestStore.heartbeatExecutionLease(parentWorkflowId, updated, dbPathOverride)) {
        FeatureTaskRuntimeHeartbeatTick.Renewed
      } else {
        FeatureTaskRuntimeHeartbeatTick.FencingLost(
          "goal parent '$parentWorkflowId' execution lease fencing was lost",
        )
      }
    }
    // Registered only for the span this process owns the lease: a runner killed from outside records
    // why it stopped, so an operator stop is never indistinguishable from a crash.
    val shutdownHook = Thread { recordInterruption(parentWorkflowId, dbPathOverride) }
    // Both registration and removal tolerate a shutdown already in progress rather than failing the run.
    runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
    val result = try {
      block()
    } finally {
      runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
      heartbeat.stop()
      manifestStore.releaseExecutionLease(
        parentWorkflowId,
        lease.ownerToken,
        lease.generation,
        dbPathOverride,
      )
    }
    // Checked after the block rather than inside the finally so a failing block reports its own cause.
    heartbeat.fencingLostReason()?.let { reason ->
      throw GoalRunnerExecutionAlreadyRunningException(parentWorkflowId, reason)
    }
    return result
  }

  /**
   * The whole shutdown-hook body: exactly one durable write, bounded, and silent on failure. It runs
   * on an already-dying JVM, so it never terminates anything, never blocks past
   * [SHUTDOWN_WRITE_BUDGET], and never lets a throw degrade exit. `overwriteExistingReason = false`
   * makes it idempotent with the stop verb — a stop that killed this process already wrote the more
   * specific `operator_stop`, and the hook leaves it alone.
   */
  internal fun recordInterruption(parentWorkflowId: String, dbPathOverride: String?) {
    val writer = Thread {
      runCatching {
        manifestStore.pauseNow(
          parentWorkflowId = parentWorkflowId,
          reason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
          pausedAt = clock.instant().toString(),
          overwriteExistingReason = false,
          dbPathOverride = dbPathOverride,
        )
      }
    }
    writer.isDaemon = true
    runCatching {
      writer.start()
      writer.join(SHUTDOWN_WRITE_BUDGET.toMillis())
    }
  }

  private fun reclaimableOwnerToken(parentWorkflowId: String, existing: GoalRunnerExecutionLease): String {
    return when (supervisor.inspect(existing.asWorkerOwnership(parentWorkflowId))) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> existing.ownerToken
      FeatureTaskRuntimeProcessInspection.ExactLive ->
        cannotStart(parentWorkflowId, "another goal runner process is live")
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch ->
        cannotStart(parentWorkflowId, "the existing process owner is ambiguous")
      is FeatureTaskRuntimeProcessInspection.Unsupported ->
        cannotStart(parentWorkflowId, "the existing process owner cannot be inspected")
    }
  }

  private fun cannotStart(parentWorkflowId: String, detail: String): Nothing =
    throw GoalRunnerExecutionAlreadyRunningException(parentWorkflowId, detail)

  private fun newLease(
    existing: GoalRunnerExecutionLease?,
    process: FeatureTaskRuntimeProcessIdentity,
  ): GoalRunnerExecutionLease {
    val now = clock.instant()
    return GoalRunnerExecutionLease(
      generation = (existing?.generation ?: 0) + 1,
      ownerToken = UUID.randomUUID().toString(),
      hostIdentity = process.hostIdentity,
      bootIdentity = process.bootIdentity,
      pid = process.pid,
      processBirthToken = process.processBirthToken,
      heartbeatAt = now.toString(),
      expiresAt = now.plus(LEASE_DURATION).toString(),
    )
  }

  private companion object {
    val LEASE_DURATION: Duration = Duration.ofSeconds(30)
    const val HEARTBEAT_SECONDS: Long = 10

    /** Hard ceiling on how long a blocked database may hold up JVM shutdown. */
    val SHUTDOWN_WRITE_BUDGET: Duration = Duration.ofSeconds(2)
  }
}
