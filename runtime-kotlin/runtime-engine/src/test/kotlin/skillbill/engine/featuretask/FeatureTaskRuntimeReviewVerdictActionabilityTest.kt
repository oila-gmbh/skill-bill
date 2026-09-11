package skillbill.engine.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeReviewVerdictActionabilityTest {
  @Test
  fun `incomplete evidence coverage requests changes despite approved worker prose and minor findings`() {
    val envelope = mapOf(
      "verdict" to "approved",
      "produced_outputs" to mapOf(
        FeatureTaskRuntimeVerificationSignalKeys.EVIDENCE_COVERAGE_COMPLETE to false,
        FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS to listOf(
          mapOf(
            "finding_id" to "F-001",
            "severity" to "minor",
            "message" to "advisory only",
          ),
        ),
      ),
    )

    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        envelope,
      ),
    )
  }

  @Test
  fun `incomplete evidence coverage requests changes when findings are empty`() {
    val envelope = mapOf(
      "verdict" to "approved",
      "produced_outputs" to mapOf(
        FeatureTaskRuntimeVerificationSignalKeys.EVIDENCE_COVERAGE_COMPLETE to false,
        FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS to emptyList<Any>(),
      ),
    )

    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        envelope,
      ),
    )
  }

  @Test
  fun `a refuted major does not force changes_requested when only minors remain actionable`() {
    val envelope = mapOf(
      "verdict" to "approved",
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "finding_id" to "F-001",
            "severity" to "major",
            "message" to "refuted major that must not reopen review_fix",
            "claim_verdict" to "refuted",
          ),
          mapOf(
            "finding_id" to "F-002",
            "severity" to "minor",
            "message" to "advisory only",
            "claim_verdict" to "confirmed",
            "scope_disposition" to "in_scope",
          ),
        ),
      ),
    )

    assertEquals(
      FeatureTaskRuntimeVerdict.APPROVED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        envelope,
      ),
    )
    assertEquals(
      emptyList(),
      FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(envelope),
    )
  }

  @Test
  fun `a confirmed major still opens remediation`() {
    val envelope = mapOf(
      "verdict" to "approved",
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "finding_id" to "F-001",
            "severity" to "major",
            "message" to "still actionable",
            "claim_verdict" to "confirmed",
            "scope_disposition" to "in_scope",
          ),
        ),
      ),
    )

    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        envelope,
      ),
    )
    assertEquals(
      1,
      FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(envelope).size,
    )
  }

  @Test
  fun `findings without claim_verdict stay severity-driven for legacy envelopes`() {
    val envelope = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "finding_id" to "F-001",
            "severity" to "major",
            "message" to "legacy major without adjudication overlay",
          ),
        ),
      ),
    )

    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        envelope,
      ),
    )
  }
}
