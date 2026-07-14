@file:Suppress("MaxLineLength")

package skillbill.review.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.workflow.model.CodeReviewExecutionMode

class ReviewContextModelsTest {
  @Test fun `default budget is governed`() { assertEquals(524_288, ReviewContextBudgetPolicy.DEFAULT.maxParentPacketBytes); assertEquals(96_000, ReviewContextBudgetPolicy.DEFAULT.providerTokenThresholds.totalTokens) }
  @Test fun `inconsistent budgets loud fail`() { assertFailsWith<IllegalArgumentException> { ReviewContextBudgetPolicy(maxLaneEvidenceBytes = 10, maxEvidenceResultBytes = 11) } }
  @Test fun `assignment digest is stable`() {
    fun value(paths: List<String>, criteria: List<String>) = ReviewAssignment("review", "a".repeat(64), "security", "base", "head", paths, listOf("hunk"), criteria)
    assertEquals(value(listOf("b.kt", "a.kt"), listOf("2", "1")).digest, value(listOf("a.kt", "b.kt"), listOf("1", "2")).digest)
  }
  @Test fun `paths reject traversal`() { assertFailsWith<IllegalArgumentException> { ReviewAssignment("review", "a".repeat(64), "security", "base", "head", listOf("../secret"), emptyList()) } }
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
