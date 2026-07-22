package skillbill.infrastructure.fs

import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import kotlin.test.Test
import kotlin.test.assertEquals

class JdkFeatureTaskRuntimeWorkerSupervisorTest {
  @Test
  fun `worker from a previous boot on this host is not running`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor()
    val current = supervisor.currentProcess()
    val ownership = FeatureTaskRuntimeWorkerOwnership(
      workflowId = "wftr-test",
      generation = 1,
      ownerToken = "owner-token-0001",
      hostIdentity = current.hostIdentity,
      bootIdentity = "previous-${current.bootIdentity}",
      pid = current.pid,
      processBirthToken = current.processBirthToken,
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = "2026-07-14T10:00:00Z",
      expiresAt = "2026-07-14T10:00:30Z",
      phaseId = "implement",
      phaseAttempt = 1,
    )

    assertEquals(FeatureTaskRuntimeProcessInspection.NotRunning, supervisor.inspect(ownership))
  }
}
