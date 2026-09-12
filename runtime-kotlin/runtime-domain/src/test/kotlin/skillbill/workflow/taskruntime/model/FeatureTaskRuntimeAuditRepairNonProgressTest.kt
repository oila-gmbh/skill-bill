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
  fun `missing fingerprints allow repair progress when the audit closes criteria without new gaps`() {
    val fingerprints = listOf(
      UNPROVEN_REPOSITORY_FINGERPRINT to "after",
      "before" to UNPROVEN_REPOSITORY_FINGERPRINT,
      UNPROVEN_REPOSITORY_FINGERPRINT to UNPROVEN_REPOSITORY_FINGERPRINT,
      "" to "after",
      "before" to "",
    )
    fingerprints.forEach { (before, after) ->
      val decision = detectAuditRepairNonProgress(
        previous = FeatureTaskRuntimeAuditRepairSnapshot(true, before, setOf("AC-001", "AC-002")),
        current = FeatureTaskRuntimeAuditRepairSnapshot(true, after, setOf("AC-002")),
      )
      assertFalse(decision.blocked, "Expected criterion progress for fingerprints '$before' and '$after'.")
      assertTrue(requireNotNull(decision.reason).contains("acceptance-criteria fallback"))
    }
  }

  @Test
  fun `a failed current fingerprint cannot turn unchanged replaced or missing gaps into progress`() {
    val previous = FeatureTaskRuntimeAuditRepairSnapshot(true, "before", setOf("AC-001", "AC-002"))
    val currentGapSets = listOf(
      setOf("AC-001", "AC-002"),
      setOf("AC-001", "AC-002", "AC-003"),
      setOf("AC-003"),
      emptySet(),
    )
    currentGapSets.forEach { gaps ->
      val decision = detectAuditRepairNonProgress(
        previous,
        FeatureTaskRuntimeAuditRepairSnapshot(true, UNPROVEN_REPOSITORY_FINGERPRINT, gaps),
      )
      assertTrue(decision.blocked, "Unresolved criteria '$gaps' do not establish progress.")
      assertTrue(requireNotNull(decision.reason).contains("acceptance-criteria fallback"))
      assertFalse(requireNotNull(decision.reason).contains("fingerprint is unchanged"))
    }
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
  fun `changed unresolved criterion set proves progress even when fingerprint is unchanged`() {
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
      ),
    )
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
      ),
    )
    assertTrue(decision.blocked)
  }
}
