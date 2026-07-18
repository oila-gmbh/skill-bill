package skillbill.workflow.taskruntime

import kotlin.test.Test
import kotlin.test.assertFailsWith
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult

class FeatureTaskRuntimeAuditRepairModelsTest {
  @Test
  fun `complete plan accepts exact terminal results`() {
    val plan = plan(listOf(item("GAP-001-R01"), item("GAP-001-R02", listOf("GAP-001-R01"))))
    plan.requireExactCriterionCoverage(listOf("AC-001"))
    plan.requireTerminalResults(plan.gaps.flatMap { it.repairItems }.map { repair ->
      FeatureTaskRuntimeRepairItemResult(
        repair.repairItemId,
        FeatureTaskRuntimeRepairItemOutcome.FIXED,
        listOf("runtime-kotlin"),
        listOf("focused test passed"),
        "Repository target is present.",
      )
    })
  }

  @Test
  fun `dependency cycles fail loudly`() {
    assertFailsWith<IllegalArgumentException> {
      plan(listOf(item("GAP-001-R01", listOf("GAP-001-R02")), item("GAP-001-R02", listOf("GAP-001-R01"))))
    }
  }

  @Test
  fun `partial repair result set fails loudly`() {
    val plan = plan(listOf(item("GAP-001-R01"), item("GAP-001-R02")))
    assertFailsWith<IllegalArgumentException> {
      plan.requireTerminalResults(listOf(result("GAP-001-R01")))
    }
  }

  private fun plan(items: List<FeatureTaskRuntimeRepairItem>) = FeatureTaskRuntimeAuditRepairPlan(
    "0.1",
    listOf(FeatureTaskRuntimeAuditGap("GAP-001", "AC-001", "Criterion", "Evidence", "Cause", "runtime", items)),
  )

  private fun item(id: String, dependencies: List<String> = emptyList()) = FeatureTaskRuntimeRepairItem(
    id, "Outcome", listOf("Implement it"), listOf("symbol"), listOf("Run test"), dependencies,
  )

  private fun result(id: String) = FeatureTaskRuntimeRepairItemResult(
    id, FeatureTaskRuntimeRepairItemOutcome.ALREADY_SATISFIED, listOf("symbol"), listOf("test passed"), "Evidence",
  )
}
