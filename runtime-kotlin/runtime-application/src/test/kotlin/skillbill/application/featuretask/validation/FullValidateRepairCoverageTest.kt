package skillbill.application.featuretask.validation

import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.taskruntime.model.FullValidateRepairPlanItem
import skillbill.workflow.taskruntime.model.FullValidateSubstantiationReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullValidateRepairCoverageTest {
  private val first = ValidationGateFinding("app", "one", "broken", "A.kt")
  private val second = ValidationGateFinding("app", "two", "broken", null)

  @Test
  fun `identity uses an empty location segment`() {
    assertEquals("app|two|broken|", second.identity())
    assertEquals("app|one|broken|A.kt", first.identity())
  }

  @Test
  fun `grouped plan item is incomplete when one identity lacks a covering receipt`() {
    val grouped = listOf(FullValidateRepairPlanItem(listOf(first.identity(), second.identity())))
    val onlyFirst = listOf(receipt(first))
    val groupedCoverage = FullValidateRepairCoverage.evaluate(
      requiredIdentities = listOf(first.identity(), second.identity()),
      plan = grouped,
      receipts = onlyFirst,
    )
    assertFalse(groupedCoverage.accepted)

    val otherOnly = FullValidateRepairCoverage.evaluate(
      requiredIdentities = listOf(second.identity()),
      plan = listOf(FullValidateRepairPlanItem(listOf(second.identity()))),
      receipts = listOf(receipt(second)),
    )
    assertTrue(otherOnly.accepted)
    assertEquals("", otherOnly.reason)
  }

  private fun receipt(finding: ValidationGateFinding): FullValidateSubstantiationReceipt =
    FullValidateSubstantiationReceipt(
      identity = finding.identity(),
      rootCause = "shared root",
      changedPathsOrSymbols = listOf(finding.location ?: finding.ruleOrTestId),
      rationale = "fixed",
    )
}
