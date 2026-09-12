package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditRepairNonProgressTest {
  @Test
  fun `changed files cannot excuse recurring substituted or growing gaps`() {
    val previous = snapshot(setOf("AC-001", "AC-002"), "before")
    listOf(
      setOf("AC-001", "AC-002"),
      setOf("AC-003"),
      setOf("AC-001", "AC-002", "AC-003"),
      emptySet(),
    ).forEach { gaps ->
      assertTrue(detectAuditRepairNonProgress(previous, snapshot(gaps, "after")).blocked)
    }
  }

  @Test
  fun `criterion closure permits progress even when either fingerprint is unavailable`() {
    listOf("before", UNPROVEN_REPOSITORY_FINGERPRINT).forEach { before ->
      listOf("after", UNPROVEN_REPOSITORY_FINGERPRINT).forEach { after ->
        assertFalse(
          detectAuditRepairNonProgress(
            snapshot(setOf("AC-001", "AC-002"), before),
            snapshot(setOf("AC-002"), after),
          ).blocked,
        )
      }
    }
  }

  @Test
  fun `first diagnosis and satisfied audit do not require an earlier reduction`() {
    val gaps = snapshot(setOf("AC-001"), "same")
    val satisfied = gaps.copy(hasGaps = false, criterionRefs = emptySet())
    assertFalse(detectAuditRepairNonProgress(satisfied, gaps).blocked)
    assertFalse(detectAuditRepairNonProgress(gaps, satisfied).blocked)
  }

  private fun snapshot(criteria: Set<String>, fingerprint: String) =
    FeatureTaskRuntimeAuditRepairSnapshot(true, fingerprint, criteria)
}
