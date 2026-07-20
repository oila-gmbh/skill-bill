package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeAuditVerdictCoherenceTest {
  @Test
  fun `failing criteria alias is rejected at the audit gate naming the canonical key`() {
    val aliasEnvelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "produced_outputs" to mapOf(
        "failing_criteria" to listOf(mapOf("acceptance_criterion_ref" to "AC-001", "message" to "unmet")),
      ),
    )

    val reason = assertNotNull(
      auditVerificationSignalGateReason(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT, aliasEnvelope),
    )
    assertContains(reason, "failing_criteria")
    assertContains(reason, "unmet_criteria")
    assertContains(reason, "audit_repair_plan")
  }

  @Test
  fun `failing criteria alias never derives a gaps found verdict`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "produced_outputs" to mapOf(
        "failing_criteria" to listOf(mapOf("acceptance_criterion_ref" to "AC-001", "message" to "unmet")),
      ),
    )
    val output = FeatureTaskRuntimePhaseOutput(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      iteration = 1,
      payload = "{}",
      normalizedOutput = NormalizedFeatureTaskRuntimePhaseOutput(canonicalJson = "{}", envelope = envelope),
    )

    assertEquals(
      FeatureTaskRuntimeVerdict.ADVANCE,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        output,
      ),
    )
    assertEquals(emptyList(), FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(output))
  }

  @Test
  fun `satisfied verdict rejects absent or non-array unmet criteria`() {
    listOf(
      mapOf("verdict" to "satisfied", "produced_outputs" to mapOf("evidence" to "complete")),
      mapOf("verdict" to "satisfied", "produced_outputs" to mapOf("unmet_criteria" to "none")),
    ).forEach { envelope ->
      assertNotNull(FeatureTaskRuntimeOutputVerification.auditGapPayloadError(envelope))
    }
  }

  @Test
  fun `satisfied verdict rejects nonempty unmet criteria`() {
    assertNotNull(
      FeatureTaskRuntimeOutputVerification.auditGapPayloadError(
        mapOf(
          "verdict" to "satisfied",
          "produced_outputs" to mapOf("unmet_criteria" to listOf(mapOf("message" to "gap"))),
        ),
      ),
    )
  }

  @Test
  fun `gaps found verdict rejects empty unmet criteria`() {
    assertNotNull(
      FeatureTaskRuntimeOutputVerification.auditGapPayloadError(
        mapOf(
          "verdict" to "gaps_found",
          "produced_outputs" to mapOf("unmet_criteria" to emptyList<Any?>()),
        ),
      ),
    )
  }
}
