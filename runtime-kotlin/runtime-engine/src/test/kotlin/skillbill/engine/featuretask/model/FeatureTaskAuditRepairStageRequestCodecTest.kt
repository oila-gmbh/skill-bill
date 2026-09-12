package skillbill.engine.featuretask.model

import skillbill.contracts.workflow.AuditRepairCycleKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskAuditRepairStageRequestCodecTest {
  @Test
  fun `non-diagnosis stage derives omitted revision from expected revision`() {
    val request = FeatureTaskAuditRepairStageRequestCodec.fromMap(
      mapOf(
        AuditRepairCycleKeys.WORKFLOW_ID to "wftr-test",
        AuditRepairCycleKeys.AUDIT_ATTEMPT to 1,
        AuditRepairCycleKeys.EXECUTION_ID to "execution-test",
        AuditRepairCycleKeys.SESSION_ID to "session-test",
        AuditRepairCycleKeys.CYCLE_ID to "cycle-test",
        AuditRepairCycleKeys.OWNER_TOKEN to "owner-test",
        AuditRepairCycleKeys.FENCING_GENERATION to 1,
        AuditRepairCycleKeys.EXPECTED_REVISION to 0,
        AuditRepairCycleKeys.REQUEST_ID to "repair-test",
        AuditRepairCycleKeys.STAGE to "authorized_repair",
        AuditRepairCycleKeys.CRITERION_REFS to listOf("AC-001"),
      ),
      recordedAt = "2026-09-12T18:00:00Z",
    )

    assertEquals(1, request.revision.revision)
  }
}
