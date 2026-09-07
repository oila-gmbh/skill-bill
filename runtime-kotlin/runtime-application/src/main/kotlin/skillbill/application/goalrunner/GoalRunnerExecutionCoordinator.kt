package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Clock
import java.time.Duration

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
fun GoalRunnerExecutionLease.asWorkerOwnership(parentWorkflowId: String) = FeatureTaskRuntimeWorkerOwnership(
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
  private val clock: Clock,
  private val shutdownHookPort: ShutdownHookPort,
  private val daemonThreadPort: DaemonThreadPort,
  private val identifierGeneratorPort: IdentifierGeneratorPort,
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
    val shutdownHookRegistration = shutdownHookPort.register {
      recordInterruption(parentWorkflowId, dbPathOverride)
    }
    val result = try {
      block()
    } finally {
      shutdownHookRegistration.unregister()
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
  fun recordInterruption(parentWorkflowId: String, dbPathOverride: String?) {
    daemonThreadPort.runWithJoinBudget(
      action = {
        runCatching {
          manifestStore.pauseNow(
            parentWorkflowId = parentWorkflowId,
            reason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
            pausedAt = clock.instant().toString(),
            overwriteExistingReason = false,
            dbPathOverride = dbPathOverride,
          )
        }
      },
      joinBudgetMillis = SHUTDOWN_WRITE_BUDGET.toMillis(),
    )
  }

  private fun reclaimableOwnerToken(parentWorkflowId: String, existing: GoalRunnerExecutionLease): String {
    val ownership = existing.asWorkerOwnership(parentWorkflowId)
    return when (supervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> existing.ownerToken
      FeatureTaskRuntimeProcessInspection.ExactLive ->
        reclaimAfterLiveOwner(parentWorkflowId, existing, ownership)
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch ->
        cannotStart(parentWorkflowId, "the existing process owner is ambiguous")
      is FeatureTaskRuntimeProcessInspection.Unsupported ->
        cannotStart(parentWorkflowId, "the existing process owner cannot be inspected")
    }
  }

  /**
   * A second `skill-bill goal` in the same second (Cursor sandbox + real spawn) must wait for the
   * winner instead of exiting blocked — otherwise the agent turn ends and reaps the live runner.
   * A lease older than [DUPLICATE_LAUNCH_WINDOW] is a separate session (Claude, Codex, tmux) and
   * still fails closed so those agents keep the immediate "already running" signal.
   */
  private fun reclaimAfterLiveOwner(
    parentWorkflowId: String,
    existing: GoalRunnerExecutionLease,
    ownership: FeatureTaskRuntimeWorkerOwnership,
  ): String {
    if (isCurrentProcess(existing)) {
      cannotStart(parentWorkflowId, "this process already owns the execution lease")
    }
    if (!isDuplicateLaunchRace(existing)) {
      cannotStart(parentWorkflowId, "another goal runner process is live")
    }
    supervisor.awaitExit(ownership, DUPLICATE_LAUNCH_WINDOW)
    return when (supervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> existing.ownerToken
      FeatureTaskRuntimeProcessInspection.ExactLive ->
        cannotStart(parentWorkflowId, "another goal runner process is live")
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch ->
        cannotStart(parentWorkflowId, "the existing process owner is ambiguous")
      is FeatureTaskRuntimeProcessInspection.Unsupported ->
        cannotStart(parentWorkflowId, "the existing process owner cannot be inspected")
    }
  }

  private fun isCurrentProcess(existing: GoalRunnerExecutionLease): Boolean {
    val current = supervisor.currentProcess()
    return existing.pid == current.pid && existing.processBirthToken == current.processBirthToken
  }

  private fun isDuplicateLaunchRace(existing: GoalRunnerExecutionLease): Boolean {
    val ownerStartMs = existing.processBirthToken.toLongOrNull() ?: return false
    val ageMs = clock.millis() - ownerStartMs
    return ageMs in 0 until DUPLICATE_LAUNCH_WINDOW.toMillis()
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
      ownerToken = identifierGeneratorPort.randomToken(),
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
    val DUPLICATE_LAUNCH_WINDOW: Duration = Duration.ofSeconds(60)
    const val HEARTBEAT_SECONDS: Long = 10

    /** Hard ceiling on how long a blocked database may hold up JVM shutdown. */
    val SHUTDOWN_WRITE_BUDGET: Duration = Duration.ofSeconds(2)
  }
}
