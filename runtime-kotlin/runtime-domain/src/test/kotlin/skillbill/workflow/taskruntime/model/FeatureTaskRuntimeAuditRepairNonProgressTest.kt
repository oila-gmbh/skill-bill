package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditRepairNonProgressTest {
  @Test
  fun `satisfied current verdict is not blocked`() {
    val decision = detectAuditRepairNonProgress(
      previousHadGaps = true,
      currentHasGaps = false,
      previousRepositoryFingerprint = "same",
      currentRepositoryFingerprint = "same",
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `first gaps comparison is not blocked`() {
    val decision = detectAuditRepairNonProgress(
      previousHadGaps = false,
      currentHasGaps = true,
      previousRepositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
      currentRepositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `recurring gaps with an unchanged fingerprint blocks`() {
    val decision = detectAuditRepairNonProgress(
      previousHadGaps = true,
      currentHasGaps = true,
      previousRepositoryFingerprint = "same",
      currentRepositoryFingerprint = "same",
    )
    assertTrue(decision.blocked)
    assertTrue(requireNotNull(decision.reason).contains("envelope verdict is still gaps_found"))
    assertTrue(requireNotNull(decision.reason).contains("repository fingerprint is unchanged"))
  }

  @Test
  fun `unproven previous fingerprint fails closed`() {
    val decision = detectAuditRepairNonProgress(
      previousHadGaps = true,
      currentHasGaps = true,
      previousRepositoryFingerprint = UNPROVEN_REPOSITORY_FINGERPRINT,
      currentRepositoryFingerprint = "changed",
    )
    assertTrue(decision.blocked)
  }

  @Test
  fun `a proven repository change continues`() {
    val decision = detectAuditRepairNonProgress(
      previousHadGaps = true,
      currentHasGaps = true,
      previousRepositoryFingerprint = "before",
      currentRepositoryFingerprint = "after",
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }
}
