package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeAuditVerdictTest {
  @Test
  fun `empty unmet criteria classify as satisfied`() {
    assertEquals(FeatureTaskRuntimeVerdict.SATISFIED, FeatureTaskRuntimeAuditVerdict(emptyList()).verdict)
  }

  @Test
  fun `any unmet criterion classifies as gaps_found and the gaps are carried`() {
    val gaps = listOf(
      FeatureTaskRuntimeAuditCriterionGap("Criterion 3 not implemented"),
      FeatureTaskRuntimeAuditCriterionGap("Criterion 5 missing test"),
    )
    val verdict = FeatureTaskRuntimeAuditVerdict(gaps)
    assertEquals(FeatureTaskRuntimeVerdict.GAPS_FOUND, verdict.verdict)
    assertEquals(gaps, verdict.unmetCriteria)
  }

  @Test
  fun `a blank gap message is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeAuditCriterionGap("   ") }
  }
}
