package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeWorkerCoordinatorTest {
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
}

private class FakeWorkerSupervisor(
  initialInspection: FeatureTaskRuntimeProcessInspection,
) : FeatureTaskRuntimeWorkerSupervisor {
  private var inspection = initialInspection
  var gracefulTerminationRequested = false
  var forceTerminationRequested = false

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

  override fun startHeartbeat(intervalSeconds: Long, heartbeat: () -> Unit) = FeatureTaskRuntimeHeartbeat {}

  override fun pause(durationMillis: Long) = Unit
}

private fun ownership(expiresAt: String = "2999-01-01T00:00:30Z") = FeatureTaskRuntimeWorkerOwnership(
  workflowId = WORKFLOW_ID,
  generation = 1,
  ownerToken = "old-owner-token-0001",
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
