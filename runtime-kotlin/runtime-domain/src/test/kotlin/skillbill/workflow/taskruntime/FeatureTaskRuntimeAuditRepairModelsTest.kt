package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditRepairModelsTest {
  @Test
  fun `plan rejects gap identifiers that are not stable criterion generations`() {
    val error = assertFailsWith<IllegalArgumentException> {
      plan(listOf(item("gap-1-item-1")), gapId = "gap-1")
    }

    assertTrue(error.message.orEmpty().contains("stable criterion-generation identifier"))
  }

  @Test
  fun `plan rejects repair identifiers that are not ordered children of their gap`() {
    val error = assertFailsWith<IllegalArgumentException> {
      plan(listOf(item("ac-001-gap-1-item-9")))
    }

    assertTrue(error.message.orEmpty().contains("stable ordered child"))
  }

  @Test
  fun `dependency must precede its dependent in serialized execution order`() {
    assertFailsWith<IllegalArgumentException> {
      plan(
        listOf(
          item("ac-001-gap-1-item-2", listOf("ac-001-gap-1-item-1")),
          item("ac-001-gap-1-item-1"),
        ),
      )
    }
  }

  @Test
  fun `complete plan accepts exact terminal results`() {
    val plan = twoItemPlan()
    plan.requireExactCriterionCoverage(listOf("AC-001"))
    plan.requireTerminalResults(
      plan.gaps.flatMap { it.repairItems }.map { repair ->
        FeatureTaskRuntimeRepairItemResult(
          repair.repairItemId,
          FeatureTaskRuntimeRepairItemOutcome.FIXED,
          listOf("runtime-kotlin"),
          listOf("focused test passed"),
          "Repository target is present.",
        )
      },
    )
  }

  @Test
  fun `dependency cycles fail loudly`() {
    assertFailsWith<IllegalArgumentException> {
      plan(
        listOf(
          item("ac-001-gap-1-item-1", listOf("ac-001-gap-1-item-2")),
          item("ac-001-gap-1-item-2", listOf("ac-001-gap-1-item-1")),
        ),
      )
    }
  }

  @Test
  fun `partial repair result set fails loudly`() {
    val plan = plan(listOf(item("ac-001-gap-1-item-1"), item("ac-001-gap-1-item-2")))
    assertFailsWith<IllegalArgumentException> {
      plan.requireTerminalResults(listOf(result("ac-001-gap-1-item-1")))
    }
  }

  @Test
  fun `terminal results must preserve dependency execution order`() {
    val plan = twoItemPlan()

    assertFailsWith<IllegalArgumentException> {
      plan.requireTerminalResults(listOf(result("ac-001-gap-1-item-2"), result("ac-001-gap-1-item-1")))
    }
  }

  @Test
  fun `recurrence disposition must agree with durable unresolved ledger`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditRepairState(
        acceptedPlans = listOf(plan(listOf(item("ac-001-gap-1-item-1")))),
        repairItemResults = emptyList(),
        priorGapDispositions = listOf(
          FeatureTaskRuntimePriorGapDisposition(
            "GAP-001",
            FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED,
            "Verified",
          ),
        ),
        unresolvedGapLedger = FeatureTaskRuntimeUnresolvedGapLedger(
          listOf(FeatureTaskRuntimeUnresolvedGap("GAP-001", "AC-001", 1)),
        ),
        repositoryFingerprint = "digest",
        progress = FeatureTaskRuntimeAuditRepairProgress(false, 0, 0, 0, 0, 1),
      )
    }
  }

  @Test
  fun `recurring criterion reuses its durable gap identity`() {
    val ledger = FeatureTaskRuntimeUnresolvedGapLedger(
      listOf(FeatureTaskRuntimeUnresolvedGap("ac-001-gap-2", "AC-001", 2)),
    )

    assertEquals("ac-001-gap-2", ledger.allocateGapId("AC-001"))
    assertEquals("ac-002-gap-1", ledger.allocateGapId("AC-002"))
  }

  @Test
  fun `equivalent gaps without repository change block as non progress`() {
    val decision = detectAuditRepairNonProgress(setOf("gap-1"), setOf("gap-1"), "digest", "digest", 0)

    assertTrue(decision.blocked)
    assertTrue(requireNotNull(decision.reason).contains("repository fingerprint"))
  }

  @Test
  fun `changed gaps with resolved work remain eligible to continue`() {
    val decision = detectAuditRepairNonProgress(setOf("gap-1"), setOf("gap-2"), "before", "after", 1)

    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  private fun plan(items: List<FeatureTaskRuntimeRepairItem>, gapId: String = "ac-001-gap-1") =
    FeatureTaskRuntimeAuditRepairPlan(
      "0.1",
      listOf(FeatureTaskRuntimeAuditGap(gapId, "AC-001", "Criterion", "Evidence", "Cause", "runtime", items)),
    )

  private fun item(id: String, dependencies: List<String> = emptyList()) = FeatureTaskRuntimeRepairItem(
    id,
    "Outcome",
    listOf("Implement it"),
    listOf("symbol"),
    listOf("Run test"),
    dependencies,
  )

  private fun twoItemPlan() = plan(
    listOf(
      item("ac-001-gap-1-item-1"),
      item("ac-001-gap-1-item-2", listOf("ac-001-gap-1-item-1")),
    ),
  )

  private fun result(id: String) = FeatureTaskRuntimeRepairItemResult(
    id,
    FeatureTaskRuntimeRepairItemOutcome.ALREADY_SATISFIED,
    listOf("symbol"),
    listOf("test passed"),
    "Evidence",
  )
}
