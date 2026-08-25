package skillbill.application

import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.GoalRunnerExecutionAlreadyRunningException
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test
  fun `a heartbeat tick renews the parent lease while this runner still owns it`() {
    val store = InMemoryExecutionLeaseStore(lease(generation = 1, ownerToken = "old-owner"))
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    val coordinator = DefaultGoalRunnerExecutionCoordinator(store, supervisor, fixedClock())

    coordinator.runOwned("parent-1", null) {
      assertEquals(FeatureTaskRuntimeHeartbeatTick.Renewed, supervisor.runHeartbeatTick())
      assertEquals(2, requireNotNull(store.executionLeaseValue).generation)
    }

    assertEquals("parent-1", supervisor.capturedPlan?.label)
    assertEquals(PARENT_LEASE_SECONDS, supervisor.capturedPlan?.leaseSeconds)
  }

  @Test
  fun `a parent heartbeat tick that lost fencing fails the goal instead of reporting success`() {
    val store = InMemoryExecutionLeaseStore(lease(generation = 1, ownerToken = "old-owner"))
    val supervisor = FakeGoalSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    val coordinator = DefaultGoalRunnerExecutionCoordinator(store, supervisor, fixedClock())

    val failure = assertFailsWith<GoalRunnerExecutionAlreadyRunningException> {
      coordinator.runOwned("parent-1", null) {
        store.executionLeaseValue = lease(generation = 9, ownerToken = "usurper-owner")
        assertTrue(supervisor.runHeartbeatTick() is FeatureTaskRuntimeHeartbeatTick.FencingLost)
      }
    }

    assertTrue(failure.message.orEmpty().contains("execution lease fencing was lost"))
  }
}

private const val PARENT_LEASE_SECONDS = 30L

class GoalRunnerShutdownHookTest {
  @Test
  fun `the hook records a runner interruption with the injected clock in one write`() {
    val store = InMemoryExecutionLeaseStore(null)
    val coordinator = DefaultGoalRunnerExecutionCoordinator(store, FakeGoalSupervisor(NOT_RUNNING), fixedClock())

    coordinator.recordInterruption("parent-1", null)

    assertEquals(1, store.pauseNowCalls.size)
    assertEquals("runner_interrupted", store.pauseNowCalls.single().first)
    assertEquals("2026-08-02T10:00:00Z", store.pauseNowCalls.single().second)
    assertEquals(false, store.pauseNowCalls.single().third)
    assertTrue(store.controlStateValue.paused)
    assertEquals("2026-08-02T10:00:00Z", store.controlStateValue.pausedAt)
  }

  @Test
  fun `an interruption reason is distinguishable from an operator stop`() {
    val store = InMemoryExecutionLeaseStore(null)
    DefaultGoalRunnerExecutionCoordinator(store, FakeGoalSupervisor(NOT_RUNNING), fixedClock())
      .recordInterruption("parent-1", null)

    assertEquals("runner_interrupted", store.controlStateValue.pauseReason)
    assertTrue(store.controlStateValue.pauseReason != "operator_stop")
  }

  @Test
  fun `the hook leaves a stop-verb reason alone and performs no second write`() {
    val store = InMemoryExecutionLeaseStore(null)
    store.controlStateValue = GoalRunnerControlState(
      pauseRequested = true,
      pauseConsumed = true,
      paused = true,
      pauseReason = "operator_stop",
      pausedAt = "2026-08-02T09:00:00Z",
    )

    DefaultGoalRunnerExecutionCoordinator(store, FakeGoalSupervisor(NOT_RUNNING), fixedClock())
      .recordInterruption("parent-1", null)

    assertEquals("operator_stop", store.controlStateValue.pauseReason)
    assertEquals("2026-08-02T09:00:00Z", store.controlStateValue.pausedAt)
  }

  @Test
  fun `a throwing durable write never escapes the hook`() {
    val store = InMemoryExecutionLeaseStore(null)
    store.pauseNowFailure = { error("database is gone") }
    val coordinator = DefaultGoalRunnerExecutionCoordinator(store, FakeGoalSupervisor(NOT_RUNNING), fixedClock())

    coordinator.recordInterruption("parent-1", null)

    assertEquals(1, store.pauseNowCalls.size)
    assertFalse(store.controlStateValue.paused)
  }

  @Test
  fun `a normally completed owned run leaves no hook behind to write on exit`() {
    val store = InMemoryExecutionLeaseStore(null)
    val coordinator = DefaultGoalRunnerExecutionCoordinator(store, FakeGoalSupervisor(NOT_RUNNING), fixedClock())

    coordinator.runOwned("parent-1", null) { "done" }

    assertEquals(emptyList(), store.pauseNowCalls)
  }
}

private val NOT_RUNNING = FeatureTaskRuntimeProcessInspection.NotRunning

private class InMemoryExecutionLeaseStore(
  initialLease: GoalRunnerExecutionLease?,
) : GoalRunnerManifestStore {
  var executionLeaseValue: GoalRunnerExecutionLease? = initialLease
  var controlStateValue: GoalRunnerControlState = GoalRunnerControlState()
  val pauseNowCalls: MutableList<Triple<String, String, Boolean>> = mutableListOf()
  var pauseNowFailure: (() -> Nothing)? = null

  override fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    controlStateValue

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
    dbPathOverride: String?,
  ): GoalRunnerControlState {
    pauseNowCalls.add(Triple(reason, pausedAt, overwriteExistingReason))
    pauseNowFailure?.invoke()
    if (controlStateValue.paused && !overwriteExistingReason) return controlStateValue
    controlStateValue = controlStateValue.copy(
      pauseRequested = true,
      pauseConsumed = true,
      paused = true,
      pauseReason = reason,
      pausedAt = pausedAt,
    )
    return controlStateValue
  }

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
  private var tick: (() -> FeatureTaskRuntimeHeartbeatTick)? = null
  private var fencingLostReason: String? = null
  var capturedPlan: FeatureTaskRuntimeHeartbeatPlan? = null
    private set

  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("host", "boot", 200, "birth-200")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection = inspection

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat {
    capturedPlan = plan
    tick = heartbeat
    return object : FeatureTaskRuntimeHeartbeat {
      override fun stop() = Unit

      override fun fencingLostReason(): String? = fencingLostReason
    }
  }

  /** Drives one renewal the way the real loop does, including latching a proven fencing loss. */
  fun runHeartbeatTick(): FeatureTaskRuntimeHeartbeatTick {
    val outcome = requireNotNull(tick) { "startHeartbeat was never called." }.invoke()
    if (outcome is FeatureTaskRuntimeHeartbeatTick.FencingLost) fencingLostReason = outcome.reason
    return outcome
  }

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
