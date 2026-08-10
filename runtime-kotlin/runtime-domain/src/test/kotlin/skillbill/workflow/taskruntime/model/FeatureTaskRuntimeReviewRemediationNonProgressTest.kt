package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-178 subtask 3: non-convergence of the advance-blocking finding set pauses remediation.
 */
class FeatureTaskRuntimeReviewRemediationNonProgressTest {
  @Test
  fun `Major-only findings unchanged across two passes with no repository change reach blocked`() {
    val identities = advanceBlockingFindingIdentities(
      listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
    )
    val decision = detectReviewRemediationNonProgress(
      previous = identities,
      current = identities,
      previousRepositoryFingerprintOrDigest = "digest-a",
      currentRepositoryFingerprintOrDigest = "digest-a",
    )
    assertTrue(decision.blocked)
    assertTrue(requireNotNull(decision.reason).contains("Blocker or Major"))
  }

  @Test
  fun `same Major set with a repository change does not pause`() {
    val identities = advanceBlockingFindingIdentities(
      listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
    )
    val decision = detectReviewRemediationNonProgress(
      previous = identities,
      current = identities,
      previousRepositoryFingerprintOrDigest = "before",
      currentRepositoryFingerprintOrDigest = "after",
    )
    assertFalse(decision.blocked)
    assertEquals(null, decision.reason)
  }

  @Test
  fun `Blocker non-convergence still pauses on the same detector`() {
    val identities = advanceBlockingFindingIdentities(
      listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
    )
    val decision = detectReviewRemediationNonProgress(
      previous = identities,
      current = identities,
      previousRepositoryFingerprintOrDigest = "digest",
      currentRepositoryFingerprintOrDigest = "digest",
    )
    assertTrue(decision.blocked)
  }

  @Test
  fun `Minor and Nit never enter the advance-blocking identity set`() {
    val identities = advanceBlockingFindingIdentities(
      listOf(
        GoalSubtaskReviewCompactFinding("minor", "Naming", "Prefer clearer name"),
        GoalSubtaskReviewCompactFinding("nit", "Style", "Trailing space"),
        GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior"),
      ),
    )
    assertEquals(1, identities.identities.size)
    assertTrue(identities.identities.single().startsWith("major|"))
  }

  @Test
  fun `cleared findings are progress even when the repository fingerprint is unchanged`() {
    val previous = advanceBlockingFindingIdentities(
      listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
    )
    val decision = detectReviewRemediationNonProgress(
      previous = previous,
      current = FeatureTaskRuntimeReviewRemediationFindingIdentities(emptySet()),
      previousRepositoryFingerprintOrDigest = "digest",
      currentRepositoryFingerprintOrDigest = "digest",
    )
    assertFalse(decision.blocked)
  }
}
