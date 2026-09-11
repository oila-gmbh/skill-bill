package skillbill.engine.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeAuditGapCriterionRefsTest {
  @Test
  fun `extracts criterion refs from audit value`() {
    val output = mapOf(
      "produced_outputs" to mapOf(
        "value" to """{"gaps":[{"criterion":"AC-002","note":"missing"},{"criterion":"AC-004","note":"open"}]}""",
      ),
    )

    assertEquals(
      setOf("AC-002", "AC-004"),
      FeatureTaskRuntimeOutputVerification.auditGapCriterionRefs(output),
    )
  }

  @Test
  fun `uses the legacy marker when criterion refs are unavailable`() {
    val output = mapOf("produced_outputs" to mapOf("value" to "{}"))

    assertEquals(
      setOf("gaps_found"),
      FeatureTaskRuntimeOutputVerification.auditGapCriterionRefs(output),
    )
  }
}
