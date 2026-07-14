@file:Suppress("MaxLineLength")

package skillbill.review.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.workflow.model.CodeReviewExecutionMode

class ReviewContextModelsTest {
  @Test fun `default budget is governed`() { assertEquals(524_288, ReviewContextBudgetPolicy.DEFAULT.maxParentPacketBytes); assertEquals(96_000, ReviewContextBudgetPolicy.DEFAULT.providerTokenThresholds.totalTokens) }

  @Test fun `cached input cannot exceed or exist without input`() {
    assertFailsWith<IllegalArgumentException> { ProviderTokenUsage(cachedInputTokens = 1) }
    assertFailsWith<IllegalArgumentException> { ProviderTokenUsage(inputTokens = 1, cachedInputTokens = 2) }
  }
  @Test fun `inconsistent budgets loud fail`() { assertFailsWith<IllegalArgumentException> { ReviewContextBudgetPolicy(maxLaneEvidenceBytes = 10, maxEvidenceResultBytes = 11) } }
  @Test fun `assignment digest is stable`() {
    fun value(paths: List<String>, criteria: List<String>) = ReviewAssignment("review", "a".repeat(64), "security", "base", "head", paths, listOf("hunk"), criteria)
    assertEquals(value(listOf("b.kt", "a.kt"), listOf("2", "1")).digest, value(listOf("a.kt", "b.kt"), listOf("1", "2")).digest)
  }
  @Test fun `paths reject traversal`() { assertFailsWith<IllegalArgumentException> { ReviewAssignment("review", "a".repeat(64), "security", "base", "head", listOf("../secret"), emptyList()) } }
  @Test fun `packet digest normalizes ordering separators and line endings`() {
    fun packet(path: String, status: String) = ReviewContextPacket(
      "review", "repo", "base", "head", status, "kotlin", "kotlin", listOf("z", "a"), listOf("testing"),
      listOf(ReviewChangedHunk(path, 1, 1, 1, 1, "+line\r\n")),
    )
    assertEquals(packet("src\\A.kt", "clean\r\n").digest, packet("src/A.kt", "clean\n").digest)
  }
  @Test fun `governed Codex launches reject inherited and omitted turns`() {
    val assignment = ReviewAssignment("review", "a".repeat(64), "security", "base", "head", listOf("A.kt"), listOf("@@ -1 +1 @@"))
    val launch = GovernedReviewLaunch(assignment, "contract", "rubric", "broker", ReviewContextBudgetPolicy.DEFAULT)
    launch.requireCodexForkTurns("none")
    assertFailsWith<IllegalArgumentException> { launch.requireCodexForkTurns(null) }
    assertFailsWith<IllegalArgumentException> { launch.requireCodexForkTurns("all") }
    assertTrue("parent transcript" !in launch.canonicalPayload)
  }
  @Test fun `oversized compact launch returns typed budget evidence`() {
    val assignment = ReviewAssignment("review", "a".repeat(64), "security", "base", "head", listOf("A.kt"), emptyList())
    val policy = ReviewContextBudgetPolicy(maxParentPacketBytes = 10_000, maxLaneLaunchBytes = 10, maxLaneEvidenceBytes = 100, maxEvidenceResultBytes = 50, maxLaneResultBytes = 50)
    val outcome = GovernedReviewLaunch(assignment, "contract", "rubric", "broker", policy).budgetOutcomeOrNull()
    assertEquals(REVIEW_CONTEXT_BUDGET_EXCEEDED, outcome?.type)
  }
  @Test fun `inclusive parent is not double counted`() {
    val parent = ProviderTokenUsage(inputTokens = 100, outputTokens = 10, ownership = TokenOwnership.INCLUSIVE)
    assertEquals(parent, ReviewTreeUsage(parent, listOf(ReviewTreeUsage(ProviderTokenUsage(inputTokens = 50)))).aggregate())
  }
  @Test fun `fresh tokens separate cached input`() { assertEquals(70, ProviderTokenUsage(inputTokens = 100, cachedInputTokens = 50, outputTokens = 20).freshTokenApproximation) }
  @Test fun `explicit mode is authoritative`() {
    val risky = ReviewAutoEligibility(true, true, true)
    assertEquals(ResolvedReviewExecutionMode.INLINE, ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.INLINE, risky))
    assertEquals(ResolvedReviewExecutionMode.DELEGATED, ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.DELEGATED, ReviewAutoEligibility(false, false, false)))
    assertEquals(ResolvedReviewExecutionMode.DELEGATED, ReviewExecutionModePolicy.resolve(CodeReviewExecutionMode.AUTO, risky))
  }
}
