package skillbill.review.context

import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.TokenOwnership
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewTreeAccountingTest {
  @Test fun `nested usage is deterministic and counted exactly once`() {
    val child = ReviewAccountingInput("testing", "child", usage = usage(50, 20, 10))
    val root = ReviewAccountingInput("parent", "root", usage = usage(100, 40, 20), children = listOf(child))

    val summary = ReviewTreeAccounting.summarize("review", "packet", root)

    assertEquals(150, summary.aggregateDirectUsage.inputTokens)
    assertEquals(150, summary.aggregateInclusiveUsage.inputTokens)
    assertEquals(60, summary.aggregateInclusiveUsage.cachedInputTokens)
    assertEquals(30, summary.aggregateInclusiveUsage.outputTokens)
    assertEquals(120, summary.aggregateInclusiveUsage.freshTokenApproximation)
    assertEquals(listOf("testing"), summary.lanes.map { it.lane })
  }

  @Test fun `provider inclusive node is not added to descendants again`() {
    val root = ReviewAccountingInput(
      "parent",
      "root",
      usage = usage(200, 100, 20, TokenOwnership.INCLUSIVE),
      children = listOf(ReviewAccountingInput("child", "child", usage = usage(50, 25, 5))),
    )

    val summary = ReviewTreeAccounting.summarize("review", "packet", root)

    assertEquals(200, summary.aggregateInclusiveUsage.inputTokens)
    assertEquals(50, summary.aggregateDirectUsage.inputTokens)
  }

  private fun usage(input: Long, cached: Long, output: Long, ownership: TokenOwnership = TokenOwnership.DIRECT) =
    ProviderTokenUsage(input, cached, output, totalTokens = input + output, ownership = ownership)
}
