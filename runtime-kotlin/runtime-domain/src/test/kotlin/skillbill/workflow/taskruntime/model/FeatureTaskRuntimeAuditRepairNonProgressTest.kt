package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-205 subtask 1: the shrink-based audit-gap progress rule. An audit that cleared at least one
 * prior criterion ref is progress even when new criteria appeared; with no cleared prior ref it
 * stalls unless a proven repository change can be asserted, and an unproven previous fingerprint
 * fails closed.
 */
class FeatureTaskRuntimeAuditRepairNonProgressTest {
  @Test
  fun `shrunk set with a new ref appeared is progress`() {
    val decision = detectAuditRepairNonProgress(
      previousCriterionRefs = setOf("AC-002", "AC-003"),
      currentCriterionRefs = setOf("AC-001", "AC-002"),
      previousRepositoryFingerprint = "before",
      currentRepositoryFingerprint = "before",
    )
    assertFalse(decision.blocked, "AC-003 was cleared even though AC-001 appeared")
    assertEquals(null, decision.reason)
  }

  @Test
  fun `identical set with an unchanged fingerprint blocks`() {
    val decision = detectAuditRepairNonProgress(
      previousCriterionRefs = setOf("AC-002"),
      currentCriterionRefs = setOf("AC-002"),
      previousRepositoryFingerprint = "same",
      currentRepositoryFingerprint = "same",
    )
    assertTrue(decision.blocked)
    assertTrue(requireNotNull(decision.reason).contains("Audit made no progress"))
    assertTrue(requireNotNull(decision.reason).contains("repository fingerprint is unchanged"))
  }

  @Test
  fun `equal-cardinality substitution without clears and an unchanged fingerprint blocks`() {
    val decision = detectAuditRepairNonProgress(
      previousCriterionRefs = setOf("AC-002"),
      currentCriterionRefs = setOf("AC-003"),
      previousRepositoryFingerprint = "same",
      currentRepositoryFingerprint = "same",
    )
    assertTrue(decision.blocked, "AC-002 was not cleared; the unmet set did not shrink")
  }

  @Test
  fun `unproven previous fingerprint with no clears fails closed`() {
    val decision = detectAuditRepairNonProgress(
      previousCriterionRefs = setOf("AC-002"),
      currentCriterionRefs = setOf("AC-002", "AC-003"),
      previousRepositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
      currentRepositoryFingerprint = "changed",
    )
    assertTrue(decision.blocked, "change cannot be proven against the previous round, so it fails closed")
  }

  @Test
  fun `a proven repository change with no clears continues`() {
    val decision = detectAuditRepairNonProgress(
      previousCriterionRefs = setOf("AC-002"),
      currentCriterionRefs = setOf("AC-002"),
      previousRepositoryFingerprint = "before",
      currentRepositoryFingerprint = "after",
    )
    assertFalse(decision.blocked, "a proven change is evidence the tree moved")
    assertEquals(null, decision.reason)
  }

  @Test
  fun `empty current set is convergence`() {
    val decision = detectAuditRepairNonProgress(
      previousCriterionRefs = setOf("AC-002"),
      currentCriterionRefs = emptySet(),
      previousRepositoryFingerprint = "same",
      currentRepositoryFingerprint = "same",
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `empty previous set means no comparison is possible`() {
    val decision = detectAuditRepairNonProgress(
      previousCriterionRefs = emptySet(),
      currentCriterionRefs = setOf("AC-002"),
      previousRepositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
      currentRepositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }
}
