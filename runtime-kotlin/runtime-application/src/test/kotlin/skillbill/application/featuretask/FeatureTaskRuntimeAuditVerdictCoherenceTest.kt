package skillbill.application.featuretask

import kotlin.test.Test
import kotlin.test.assertNotNull

class FeatureTaskRuntimeAuditVerdictCoherenceTest {
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
