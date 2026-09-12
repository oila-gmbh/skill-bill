package skillbill.engine

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.error.AuditRepairCycleConflictError
import skillbill.error.InvalidAuditRepairCycleSchemaError
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.InMemoryAuditRepairCycleRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuditRepairPersistedRejectionTest {
  @Test
  fun `persisted audit cycle failures retain evidence and block downstream phases without escaping the runner`() {
    listOf(
      AuditRepairCycleConflictError("Audit cycle has no satisfied final assessment."),
      InvalidAuditRepairCycleSchemaError("Durable stage disagrees with its evidence."),
    ).forEach { failure ->
      val stored = InMemoryFeatureTaskPhaseSettlementRepository()
      val claim = FeatureTaskPhaseSettlement(
        workflowId = WORKFLOW_ID,
        phaseId = "audit",
        attempt = 1,
        kind = FeatureTaskPhaseSettlementService.KIND_AUDIT_SETTLE,
        envelopeJson = JsonCodec.mapToJsonString(
          ProsePhaseOutputSynthesizer.envelopeFromSettlement(
            SettlementEnvelopeRequest(
              phaseId = "audit",
              status = "completed",
              value = "Persisted final audit claim.",
              summary = "Audit claim.",
              verdict = "satisfied",
            ),
          ),
        ),
        recordedAt = testHarnessClock.instant().toString(),
      )
      stored.upsert(claim)
      val settlements = object : FeatureTaskPhaseSettlementRepository by stored {
        override fun find(workflowId: String, phaseId: String, attempt: Int): FeatureTaskPhaseSettlement? =
          if (phaseId == "audit") claim.copy(attempt = attempt) else stored.find(workflowId, phaseId, attempt)
      }
      val cycles = object : AuditRepairCycleRepository by InMemoryAuditRepairCycleRepository() {
        override fun findForAttempt(workflowId: String, auditAttempt: Int): AuditRepairCycle? = throw failure
      }
      val harness = runnerHarness(
        RuntimeHarnessConfig(
          agentAssignment = phasePerAgentAssignment(),
          phaseSettlementService = FeatureTaskPhaseSettlementService(settlements, testHarnessClock, cycles),
        ),
      )

      val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
      assertFalse("audit" in harness.launchOrder())
      assertFalse("review" in harness.launchOrder())
      assertFalse("validate" in harness.launchOrder())
      assertEquals(claim, stored.find(WORKFLOW_ID, "audit", 1))
      assertTrue(report.blockedReason.orEmpty().contains("Durable audit recovery"))
    }
  }
}
