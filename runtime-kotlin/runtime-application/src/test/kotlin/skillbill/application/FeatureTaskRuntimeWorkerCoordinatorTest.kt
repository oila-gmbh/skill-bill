package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeWorkerCoordinatorTest {
  @Test
  fun `unowned acquire still claims after a concurrent updated_at bump`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.saveFeatureTaskRuntimeWorkflow(unownedRuntimeRow(updatedAt = "2026-08-15T20:57:11Z"))
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(
      BumpUpdatedAtAfterReadDatabase(repository),
      FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
    )

    coordinator.runOwned(WORKFLOW_ID, null) {
      val owned = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(1, owned.generation)
      assertEquals("implement", owned.phaseId)
    }

    assertNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
  }

  @Test
  fun `orphaned worker lease is atomically reclaimed with a new generation`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(
      RuntimeFakeDatabaseSessionFactory(repository),
      FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
    )

    coordinator.runOwned(WORKFLOW_ID, null) {
      val replacement = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(2, replacement.generation)
      assertNotEquals("old-owner-token-0001", replacement.ownerToken)
    }

    assertNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
  }

  @Test
  fun `exact live worker is stopped before ownership transfer`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor = FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive)
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(RuntimeFakeDatabaseSessionFactory(repository), supervisor)

    coordinator.runOwned(WORKFLOW_ID, null) { Unit }

    assertTrue(supervisor.gracefulTerminationRequested)
    assertEquals(false, supervisor.forceTerminationRequested)
  }

  @Test
  fun `PID reuse ownership mismatch rejects takeover without terminating`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor = FakeWorkerSupervisor(
      FeatureTaskRuntimeProcessInspection.OwnershipMismatch("Worker PID was reused by a different process."),
    )
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(RuntimeFakeDatabaseSessionFactory(repository), supervisor)

    val failure = assertFailsWith<IllegalStateException> { coordinator.runOwned(WORKFLOW_ID, null) { Unit } }

    assertTrue(failure.message.orEmpty().contains("PID was reused"))
    assertEquals(false, supervisor.gracefulTerminationRequested)
  }

  @Test
  fun `expired ownership mismatch is reclaimed without terminating an unrelated process`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership(expiresAt = "2000-01-01T00:00:30Z"))
    val supervisor = FakeWorkerSupervisor(
      FeatureTaskRuntimeProcessInspection.OwnershipMismatch("Worker ownership belongs to a different host."),
    )
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(RuntimeFakeDatabaseSessionFactory(repository), supervisor)

    coordinator.runOwned(WORKFLOW_ID, null) {
      val replacement = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(2, replacement.generation)
      assertNotEquals("old-owner-token-0001", replacement.ownerToken)
    }

    assertEquals(false, supervisor.gracefulTerminationRequested)
    assertEquals(false, supervisor.forceTerminationRequested)
  }

  @Test
  fun `concurrent recovery contention permits only one lease transfer`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val stale = ownership()
    repository.seedWorkerOwnership(stale)
    assertTrue(repository.reserveFeatureTaskRuntimeWorkerTakeover(WORKFLOW_ID, stale.ownerToken, stale.generation))
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(
      RuntimeFakeDatabaseSessionFactory(repository),
      FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
    )

    val failure = assertFailsWith<IllegalStateException> { coordinator.runOwned(WORKFLOW_ID, null) { Unit } }

    assertTrue(failure.message.orEmpty().contains("Concurrent continuation"))
  }

  @Test
  fun `a heartbeat tick renews the lease while this process still owns it`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor = FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(RuntimeFakeDatabaseSessionFactory(repository), supervisor)

    coordinator.runOwned(WORKFLOW_ID, null) {
      val owned = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(FeatureTaskRuntimeHeartbeatTick.Renewed, supervisor.runHeartbeatTick())
      val renewed = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
      assertEquals(owned.ownerToken, renewed.ownerToken)
      assertTrue(Instant.parse(renewed.expiresAt) >= Instant.parse(owned.expiresAt))
    }

    assertEquals(WORKFLOW_ID, supervisor.capturedPlan?.label)
    assertEquals(LEASE_SECONDS, supervisor.capturedPlan?.leaseSeconds)
  }

  @Test
  fun `a heartbeat tick that lost fencing fails the phase instead of reporting success`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    repository.seedWorkerOwnership(ownership())
    val supervisor = FakeWorkerSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning)
    val coordinator = FeatureTaskRuntimeWorkerCoordinator(RuntimeFakeDatabaseSessionFactory(repository), supervisor)

    val failure = assertFailsWith<IllegalStateException> {
      coordinator.runOwned(WORKFLOW_ID, null) {
        repository.seedWorkerOwnership(ownership(ownerToken = "usurper-token-0002", generation = 9))
        assertTrue(supervisor.runHeartbeatTick() is FeatureTaskRuntimeHeartbeatTick.FencingLost)
      }
    }

    assertTrue(failure.message.orEmpty().contains("lost lease fencing mid-phase"))
  }
}

private const val LEASE_SECONDS = 30L

private class FakeWorkerSupervisor(
  initialInspection: FeatureTaskRuntimeProcessInspection,
) : FeatureTaskRuntimeWorkerSupervisor {
  private var inspection = initialInspection
  private var tick: (() -> FeatureTaskRuntimeHeartbeatTick)? = null
  private var fencingLostReason: String? = null
  var gracefulTerminationRequested = false
  var forceTerminationRequested = false
  var capturedPlan: FeatureTaskRuntimeHeartbeatPlan? = null
    private set

  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 200, "birth-200")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    gracefulTerminationRequested = true
    inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    return true
  }

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    forceTerminationRequested = true
    inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    return true
  }

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

private class BumpUpdatedAtAfterReadDatabase(
  private val workflows: InMemoryRuntimeWorkflowRepository,
) : DatabaseSessionFactory {
  private val inner = RuntimeFakeDatabaseSessionFactory(workflows)

  override fun resolveDbPath(dbOverride: String?) = inner.resolveDbPath(dbOverride)

  override fun databaseExists(dbOverride: String?) = inner.databaseExists(dbOverride)

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T {
    val result = inner.read(dbOverride, block)
    workflows.bumpUpdatedAt(WORKFLOW_ID)
    return result
  }

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = inner.transaction(dbOverride, block)

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T =
    inner.selfManagedWrite(dbOverride, block)
}

private fun unownedRuntimeRow(updatedAt: String) = WorkflowStateRecord(
  workflowId = WORKFLOW_ID,
  sessionId = "ftr-unowned",
  workflowName = "bill-feature-task",
  contractVersion = "0.1",
  workflowStatus = "pending",
  currentStepId = "implement",
  stepsJson = "[]",
  artifactsJson = "{}",
  startedAt = "2026-08-15T20:57:11Z",
  updatedAt = updatedAt,
  finishedAt = null,
  mode = FeatureTaskWorkflowMode.RUNTIME,
)

private fun ownership(
  expiresAt: String = "2999-01-01T00:00:30Z",
  ownerToken: String = "old-owner-token-0001",
  generation: Long = 1,
) = FeatureTaskRuntimeWorkerOwnership(
  workflowId = WORKFLOW_ID,
  generation = generation,
  ownerToken = ownerToken,
  hostIdentity = "host",
  bootIdentity = "boot",
  pid = 100,
  processBirthToken = "birth-100",
  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  heartbeatAt = "2026-07-14T10:00:00Z",
  expiresAt = expiresAt,
  phaseId = "implement",
  phaseAttempt = 1,
)
