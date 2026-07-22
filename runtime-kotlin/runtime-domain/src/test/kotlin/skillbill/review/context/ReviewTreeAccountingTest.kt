package skillbill.review.context

import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.TokenOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

  @Test fun `counters aggregate inclusively without double counting a session`() {
    val root = ReviewAccountingInput(
      "parent",
      "root",
      counters = ReviewAccountingCounters(10, 1, 2, 1, 1, 1),
      children = listOf(
        ReviewAccountingInput(
          "architecture",
          "arch",
          counters = ReviewAccountingCounters(100, 200, 300, 2, 3, 4),
        ),
        ReviewAccountingInput(
          "testing",
          "test",
          counters = ReviewAccountingCounters(1000, 2000, 3000, 5, 6, 7),
        ),
      ),
    )

    val summary = ReviewTreeAccounting.summarize("review", "packet", root)

    assertEquals(1110, summary.aggregateCounters.launchBytes)
    assertEquals(2201, summary.aggregateCounters.evidenceBytes)
    assertEquals(3302, summary.aggregateCounters.resultBytes)
    assertEquals(8, summary.aggregateCounters.expansions)
    assertEquals(10, summary.aggregateCounters.toolCalls)
    assertEquals(12, summary.aggregateCounters.modelTurns)
    assertEquals(summary.aggregateCounters, summary.parent.inclusiveCounters)
    assertEquals(10, summary.parent.counters.launchBytes)
    summary.lanes.forEach { lane -> assertEquals(lane.counters, lane.inclusiveCounters) }
  }

  @Test fun `mixed direct and inclusive ownership keeps both aggregates attributable`() {
    val root = ReviewAccountingInput(
      "parent",
      "root",
      children = listOf(
        ReviewAccountingInput(
          "agent-1",
          "a1",
          usage = usage(400, 100, 40, TokenOwnership.INCLUSIVE),
          children = listOf(ReviewAccountingInput("architecture", "arch", usage = usage(90, 10, 9))),
        ),
        ReviewAccountingInput(
          "agent-2",
          "a2",
          children = listOf(ReviewAccountingInput("testing", "test", usage = usage(70, 20, 7))),
        ),
      ),
    )

    val summary = ReviewTreeAccounting.summarize("review", "packet", root)

    assertEquals(160, summary.aggregateDirectUsage.inputTokens)
    assertEquals(470, summary.aggregateInclusiveUsage.inputTokens)
    assertEquals(400, summary.parent.children.first().inclusiveUsage.inputTokens)
  }

  @Test fun `missing provider dimensions stay absent instead of becoming zero`() {
    val root = ReviewAccountingInput(
      "parent",
      "root",
      children = listOf(
        ReviewAccountingInput("architecture", "arch", usage = ProviderTokenUsage(outputTokens = 12)),
      ),
    )

    val summary = ReviewTreeAccounting.summarize("review", "packet", root)

    assertEquals(null, summary.aggregateInclusiveUsage.inputTokens)
    assertEquals(null, summary.aggregateInclusiveUsage.reasoningTokens)
    assertEquals(12, summary.aggregateInclusiveUsage.outputTokens)
    assertEquals(12, summary.aggregateInclusiveUsage.freshTokenApproximation)
  }

  @Test fun `cached input above input is rejected at the usage boundary`() {
    assertFailsWith<IllegalArgumentException> { ProviderTokenUsage(inputTokens = 10, cachedInputTokens = 11) }
  }

  @Test fun `lane ordering is stable and independent of input order`() {
    fun tree(order: List<String>) = ReviewTreeAccounting.summarize(
      "review",
      "packet",
      ReviewAccountingInput(
        "parent",
        "root",
        children = order.map { ReviewAccountingInput(it, "digest-$it") },
      ),
    ).lanes.map { it.lane }

    val sorted = listOf("architecture", "security", "testing")
    assertEquals(sorted, tree(listOf("testing", "architecture", "security")))
    assertEquals(sorted, tree(listOf("security", "testing", "architecture")))
  }

  @Test fun `budget regression anywhere in the tree raises the summary flag`() {
    val clean = ReviewTreeAccounting.summarize(
      "review",
      "packet",
      ReviewAccountingInput("parent", "root", children = listOf(ReviewAccountingInput("lane", "d"))),
    )
    val regressed = ReviewTreeAccounting.summarize(
      "review",
      "packet",
      ReviewAccountingInput(
        "parent",
        "root",
        children = listOf(
          ReviewAccountingInput(
            "agent-1",
            "a1",
            children = listOf(ReviewAccountingInput("deep", "d", terminalOutcome = "budget_regression")),
          ),
        ),
      ),
    )

    assertFalse(clean.budgetRegression)
    assertTrue(regressed.budgetRegression)
  }

  private fun usage(input: Long, cached: Long, output: Long, ownership: TokenOwnership = TokenOwnership.DIRECT) =
    ProviderTokenUsage(input, cached, output, totalTokens = input + output, ownership = ownership)
}
