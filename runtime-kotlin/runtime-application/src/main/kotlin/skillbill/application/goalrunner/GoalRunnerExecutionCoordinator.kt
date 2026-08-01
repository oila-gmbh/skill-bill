package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
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
    val heartbeat = supervisor.startHeartbeat(HEARTBEAT_SECONDS) {
      val now = clock.instant()
      val updated = lease.copy(heartbeatAt = now.toString(), expiresAt = now.plus(LEASE_DURATION).toString())
      check(manifestStore.heartbeatExecutionLease(parentWorkflowId, updated, dbPathOverride)) {
        "Goal parent '$parentWorkflowId' execution lease fencing was lost."
      }
    }
    return try {
      block()
    } finally {
      heartbeat.stop()
      manifestStore.releaseExecutionLease(
        parentWorkflowId,
        lease.ownerToken,
        lease.generation,
        dbPathOverride,
      )
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

  private fun GoalRunnerExecutionLease.asWorkerOwnership(parentWorkflowId: String) = FeatureTaskRuntimeWorkerOwnership(
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

  private companion object {
    val LEASE_DURATION: Duration = Duration.ofSeconds(30)
    const val HEARTBEAT_SECONDS: Long = 10
  }
}
