package skillbill.application

import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.GoalRunnerExecutionAlreadyRunningException
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalRunnerExecutionCoordinatorTest {
  @Test
  fun `confirmed dead parent lease is reclaimed with a new generation`() {
    val store = InMemoryExecutionLeaseStore(lease(generation = 1, ownerToken = "old-owner"))
    val coordinator = DefaultGoalRunnerExecutionCoordinator(
      manifestStore = store,
      supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
      clock = fixedClock(),
    )

    val result = coordinator.runOwned("parent-1", null) {
      assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
      "continued"
    }

    assertEquals("continued", result)
    assertNull(store.executionLeaseValue)
  }

  @Test
  fun `live parent lease blocks a second foreground goal runner`() {
    val store = InMemoryExecutionLeaseStore(lease(generation = 1, ownerToken = "live-owner"))
    val coordinator = DefaultGoalRunnerExecutionCoordinator(
      manifestStore = store,
      supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive),
      clock = fixedClock(),
    )

    assertFailsWith<GoalRunnerExecutionAlreadyRunningException> {
      coordinator.runOwned("parent-1", null) { error("the second run must not enter the goal body") }
    }
    assertEquals("live-owner", requireNotNull(store.executionLeaseValue).ownerToken)
  }
}

private class InMemoryExecutionLeaseStore(
  initialLease: GoalRunnerExecutionLease?,
) : GoalRunnerManifestStore {
  var executionLeaseValue: GoalRunnerExecutionLease? = initialLease

  override fun loadByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: java.nio.file.Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? =
    executionLeaseValue

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean {
    if (executionLeaseValue?.ownerToken != expectedOwnerToken) return false
    executionLeaseValue = lease
    return true
  }

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean {
    if (executionLeaseValue?.ownerToken != lease.ownerToken || executionLeaseValue?.generation != lease.generation) {
      return false
    }
    executionLeaseValue = lease
    return true
  }

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean {
    if (executionLeaseValue?.ownerToken != ownerToken || executionLeaseValue?.generation != generation) return false
    executionLeaseValue = null
    return true
  }
}

private class FakeGoalSupervisor(
  private val inspection: FeatureTaskRuntimeProcessInspection,
) : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("host", "boot", 200, "birth-200")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection = inspection

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun startHeartbeat(intervalSeconds: Long, heartbeat: () -> Unit): FeatureTaskRuntimeHeartbeat =
    FeatureTaskRuntimeHeartbeat {}

  override fun pause(durationMillis: Long) = Unit
}

private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneOffset.UTC)

private fun lease(generation: Long, ownerToken: String) = GoalRunnerExecutionLease(
  generation = generation,
  ownerToken = ownerToken,
  hostIdentity = "host",
  bootIdentity = "boot",
  pid = 100,
  processBirthToken = "birth-100",
  heartbeatAt = "2026-08-02T09:59:00Z",
  expiresAt = "2026-08-02T09:59:30Z",
)
