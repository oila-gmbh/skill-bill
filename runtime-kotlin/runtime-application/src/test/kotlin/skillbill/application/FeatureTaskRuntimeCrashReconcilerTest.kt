package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeCrashLiveness
import skillbill.application.featuretask.FeatureTaskRuntimeCrashReconciler
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.ports.workflow.model.WorkflowStateRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeCrashReconcilerTest {
  @Test
  fun `only NotRunning is confirmed dead while ExactLive and ambiguous evidence stay conservative`() {
    assertTrue(FeatureTaskRuntimeCrashLiveness.isConfirmedDead(FeatureTaskRuntimeProcessInspection.NotRunning))
    assertFalse(FeatureTaskRuntimeCrashLiveness.isConfirmedDead(FeatureTaskRuntimeProcessInspection.ExactLive))
    assertFalse(
      FeatureTaskRuntimeCrashLiveness.isConfirmedDead(
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("pid reuse"),
      ),
    )
    assertFalse(
      FeatureTaskRuntimeCrashLiveness.isConfirmedDead(FeatureTaskRuntimeProcessInspection.Unsupported("no probe")),
    )
  }

  @Test
  fun `zero candidates is a no-op`() {
    val reconciler = FeatureTaskRuntimeCrashReconciler(
      RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository()),
      inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
      NoopRuntimeDiagnostics,
      testHarnessClock,
    )

    val result = reconciler.reconcile(null)

    assertEquals(0, result.reconciledCount)
    assertTrue(result.reasonClassCounts.isEmpty())
  }

  @Test
  fun `expired-lease dead-process row is transitioned once and a second pass changes nothing`() {
    val repository = crashCandidateRepository()
    val reconciler = FeatureTaskRuntimeCrashReconciler(
      RuntimeFakeDatabaseSessionFactory(repository),
      inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
      NoopRuntimeDiagnostics,
      testHarnessClock,
    )

    val first = reconciler.reconcile(null)
    val second = reconciler.reconcile(null)

    assertEquals(1, first.reconciledCount)
    assertEquals(mapOf("lease_expired" to 1), first.reasonClassCounts)
    assertEquals(0, second.reconciledCount)
    assertEquals("pending", repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID)?.workflowStatus)
    assertEquals(null, repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
  }

  @Test
  fun `a live process is never reconciled`() {
    val repository = crashCandidateRepository()
    val reconciler = FeatureTaskRuntimeCrashReconciler(
      RuntimeFakeDatabaseSessionFactory(repository),
      inspectionSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive),
      NoopRuntimeDiagnostics,
      testHarnessClock,
    )

    val result = reconciler.reconcile(null)

    assertEquals(0, result.reconciledCount)
    assertEquals("running", repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID)?.workflowStatus)
  }

  @Test
  fun `ambiguous liveness evidence never reconciles`() {
    listOf(
      FeatureTaskRuntimeProcessInspection.OwnershipMismatch("pid reuse"),
      FeatureTaskRuntimeProcessInspection.Unsupported("no probe"),
    ).forEach { inspection ->
      val repository = crashCandidateRepository()
      val reconciler = FeatureTaskRuntimeCrashReconciler(
        RuntimeFakeDatabaseSessionFactory(repository),
        inspectionSupervisor(inspection),
        NoopRuntimeDiagnostics,
        testHarnessClock,
      )

      assertEquals(0, reconciler.reconcile(null).reconciledCount)
      assertEquals("running", repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID)?.workflowStatus)
    }
  }

  @Test
  fun `an unexpected fault is counted under a distinct reason class and not as a reconciliation`() {
    val repository = crashCandidateRepository()
    val faultingSupervisor = object : FeatureTaskRuntimeWorkerSupervisor {
      override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("h", "b", 1, "birth")
      override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
        error("probe blew up")
      override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true
      override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true
      override fun startHeartbeat(
        plan: FeatureTaskRuntimeHeartbeatPlan,
        heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
      ) = NoopFeatureTaskRuntimeHeartbeat
      override fun pause(durationMillis: Long) = Unit
    }
    val reconciler = FeatureTaskRuntimeCrashReconciler(
      RuntimeFakeDatabaseSessionFactory(repository),
      faultingSupervisor,
      NoopRuntimeDiagnostics,
      testHarnessClock,
    )

    val result = reconciler.reconcile(null)

    assertEquals(0, result.reconciledCount)
    assertEquals(mapOf("reconcile_fault" to 1), result.reasonClassCounts)
    assertEquals("running", repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID)?.workflowStatus)
  }

  private fun crashCandidateRepository(): InMemoryRuntimeWorkflowRepository =
    InMemoryRuntimeWorkflowRepository().apply {
      saveFeatureTaskRuntimeWorkflow(
        WorkflowStateRecord(
          workflowId = WORKFLOW_ID,
          sessionId = SESSION_ID,
          workflowName = "bill-feature-task",
          contractVersion = "0.1",
          workflowStatus = "running",
          currentStepId = "implement",
          stepsJson = "[]",
          artifactsJson = "{}",
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
          mode = RUNTIME,
        ),
      )
      seedWorkerOwnership(
        FeatureTaskRuntimeWorkerOwnership(
          workflowId = WORKFLOW_ID,
          generation = 1,
          ownerToken = "owner-token-crashed01",
          hostIdentity = "host",
          bootIdentity = "boot",
          pid = 4242,
          processBirthToken = "birth-4242",
          leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
          heartbeatAt = "2000-01-01T00:00:00Z",
          expiresAt = "2000-01-01T00:00:30Z",
          phaseId = "implement",
          phaseAttempt = 1,
        ),
      )
    }

  private fun inspectionSupervisor(
    inspection: FeatureTaskRuntimeProcessInspection,
  ): FeatureTaskRuntimeWorkerSupervisor = object : FeatureTaskRuntimeWorkerSupervisor {
    override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("h", "b", 1, "birth")
    override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection
    override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true
    override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true
    override fun startHeartbeat(
      plan: FeatureTaskRuntimeHeartbeatPlan,
      heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
    ) = NoopFeatureTaskRuntimeHeartbeat
    override fun pause(durationMillis: Long) = Unit
  }
}
