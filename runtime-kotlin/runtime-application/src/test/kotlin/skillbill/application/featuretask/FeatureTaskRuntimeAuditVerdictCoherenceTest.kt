package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeAuditVerdictCoherenceTest {
  @Test
  fun `failing criteria alias is left for the schema diagnostic`() {
    val aliasEnvelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "produced_outputs" to mapOf(
        "failing_criteria" to listOf(mapOf("acceptance_criterion_ref" to "AC-001", "message" to "unmet")),
      ),
    )

    assertEquals(
      null,
      FeatureTaskRuntimeVerificationGateReasons.auditVerificationSignal(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        aliasEnvelope,
      ),
    )
  }

  @Test
  fun `failing criteria alias never derives a gaps found verdict`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "produced_outputs" to mapOf(
        "failing_criteria" to listOf(mapOf("acceptance_criterion_ref" to "AC-001", "message" to "unmet")),
      ),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.ADVANCE,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
    assertEquals(emptyList(), FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(envelope))
  }

  @Test
  fun `legacy unmet criteria key is left for the schema diagnostic`() {
    assertEquals(
      null,
      FeatureTaskRuntimeOutputVerification.auditGapPayloadError(
        mapOf(
          "verdict" to "gaps_found",
          "produced_outputs" to mapOf(
            "unmet_criteria" to listOf(mapOf("message" to "gap", "severity" to "major")),
          ),
        ),
      ),
    )
    assertEquals(
      null,
      FeatureTaskRuntimeOutputVerification.auditGapPayloadError(
        mapOf(
          "verdict" to "satisfied",
          "produced_outputs" to mapOf("unmet_criteria" to emptyList<Any?>()),
        ),
      ),
    )
  }

  @Test
  fun `satisfied verdict rejects absent or non-array gaps`() {
    listOf(
      mapOf("verdict" to "satisfied", "produced_outputs" to mapOf("evidence" to "complete")),
      mapOf("verdict" to "satisfied", "produced_outputs" to mapOf("gaps" to "none")),
    ).forEach { envelope ->
      assertNotNull(FeatureTaskRuntimeOutputVerification.auditGapPayloadError(envelope))
    }
  }

  @Test
  fun `satisfied verdict rejects nonempty gaps`() {
    assertNotNull(
      FeatureTaskRuntimeOutputVerification.auditGapPayloadError(
        mapOf(
          "verdict" to "satisfied",
          "produced_outputs" to mapOf(
            "gaps" to listOf(
              mapOf(
                "criterion" to "AC-001",
                "severity" to "major",
                "location" to "Example.kt",
                "issue" to "gap",
                "fix" to "fix it",
              ),
            ),
          ),
        ),
      ),
    )
  }

  @Test
  fun `gaps found verdict rejects empty gaps`() {
    assertNotNull(
      FeatureTaskRuntimeOutputVerification.auditGapPayloadError(
        mapOf(
          "verdict" to "gaps_found",
          "produced_outputs" to mapOf("gaps" to emptyList<Any?>()),
        ),
      ),
    )
  }

  @Test
  fun `minor and nit findings cannot drive audit gap`() {
    listOf("minor", "nit").forEach { severity ->
      val envelope = mapOf(
        "verdict" to "gaps_found",
        "produced_outputs" to mapOf(
          "gaps" to listOf(
            mapOf(
              "criterion" to "AC-001",
              "severity" to severity,
              "location" to "Example.kt",
              "issue" to "non-blocking",
              "fix" to "optional",
            ),
          ),
        ),
      )

      val reason = assertNotNull(FeatureTaskRuntimeOutputVerification.auditGapPayloadError(envelope))
      assertContains(reason, "non_blocking_findings")
      assertEquals(
        FeatureTaskRuntimeVerdict.SATISFIED,
        FeatureTaskRuntimeOutputVerification.verdictFor(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          envelope,
        ),
      )
    }
  }

  @Test
  fun `satisfied audit preserves non blocking findings without reopening implementation`() {
    val envelope = mapOf(
      "verdict" to "satisfied",
      "produced_outputs" to mapOf(
        "gaps" to emptyList<Any?>(),
        "non_blocking_findings" to listOf(
          mapOf("message" to "small cleanup", "severity" to "minor"),
          mapOf("message" to "naming preference", "severity" to "nit"),
        ),
      ),
    )

    assertEquals(null, FeatureTaskRuntimeOutputVerification.auditGapPayloadError(envelope))
    assertEquals(
      FeatureTaskRuntimeVerdict.SATISFIED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
    assertEquals(emptyList(), FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(envelope))
  }
}
