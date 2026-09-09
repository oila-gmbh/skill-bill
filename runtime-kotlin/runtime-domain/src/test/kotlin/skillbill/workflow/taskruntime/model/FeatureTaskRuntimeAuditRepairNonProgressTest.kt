package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditRepairNonProgressTest {
  @Test
  fun `satisfied current verdict is not blocked`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "same",
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = false,
        repositoryFingerprint = "same",
      ),
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `first gaps comparison is not blocked`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = false,
        repositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
      ),
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `recurring gaps with an unchanged fingerprint blocks`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "same",
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "same",
      ),
    )
    assertTrue(decision.blocked)
    assertTrue(requireNotNull(decision.reason).contains("envelope verdict is still gaps_found"))
    assertTrue(requireNotNull(decision.reason).contains("repository fingerprint is unchanged"))
  }

  @Test
  fun `unproven previous fingerprint fails closed`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "changed",
      ),
    )
    assertTrue(decision.blocked)
  }

  @Test
  fun `a proven repository change continues`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "before",
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "after",
      ),
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `same unresolved criteria continue when repository fingerprint changes`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "same",
        criterionRefs = setOf("AC-001", "AC-002"),
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "same",
        criterionRefs = setOf("AC-002"),
      ),    )
    assertFalse(decision.blocked)
  }

  @Test
  fun `unchanged unresolved criterion set with changed repository continues`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "before",
        criterionRefs = setOf("AC-001"),
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "after",
        criterionRefs = setOf("AC-001"),
      ),
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `unchanged unresolved criterion set with unchanged repository blocks`() {
    val decision = detectAuditRepairNonProgress(
      previous = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "same",
        criterionRefs = setOf("AC-001"),
      ),
      current = FeatureTaskRuntimeAuditRepairSnapshot(
        hasGaps = true,
        repositoryFingerprint = "same",
        criterionRefs = setOf("AC-001"),
      ),    )
    assertTrue(decision.blocked)
  }
}
