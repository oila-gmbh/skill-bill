package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditSeverity
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
  fun `blocker and major criteria classify as gaps found and are carried`() {
    val gaps = listOf(
      FeatureTaskRuntimeAuditCriterionGap("Criterion 3 not implemented", FeatureTaskRuntimeAuditSeverity.BLOCKER),
      FeatureTaskRuntimeAuditCriterionGap("Criterion 5 missing behavior"),
    )
    val verdict = FeatureTaskRuntimeAuditVerdict(gaps)
    assertEquals(FeatureTaskRuntimeVerdict.GAPS_FOUND, verdict.verdict)
    assertEquals(gaps, verdict.unmetCriteria)
  }

  @Test
  fun `minor and nit findings do not classify as gaps found`() {
    val findings = listOf(
      FeatureTaskRuntimeAuditCriterionGap("Small concern", FeatureTaskRuntimeAuditSeverity.MINOR),
      FeatureTaskRuntimeAuditCriterionGap("Style concern", FeatureTaskRuntimeAuditSeverity.NIT),
    )

    val verdict = FeatureTaskRuntimeAuditVerdict(findings)

    assertEquals(FeatureTaskRuntimeVerdict.SATISFIED, verdict.verdict)
    assertEquals(emptyList(), verdict.blockingCriteria)
  }

  @Test
  fun `a blank gap message is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeAuditCriterionGap("   ") }
  }
}
