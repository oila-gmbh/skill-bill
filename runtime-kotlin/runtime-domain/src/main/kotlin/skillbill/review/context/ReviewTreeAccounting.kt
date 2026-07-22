package skillbill.review.context

import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.TokenOwnership

data class ReviewAccountingCounters(
  val launchBytes: Long = 0,
  val evidenceBytes: Long = 0,
  val resultBytes: Long = 0,
  val expansions: Int = 0,
  val toolCalls: Int = 0,
  val modelTurns: Int = 0,
) {
  init {
    require(listOf(launchBytes, evidenceBytes, resultBytes).all { it >= 0 })
    require(listOf(expansions, toolCalls, modelTurns).all { it >= 0 })
  }
}

data class ReviewAccountingInput(
  val lane: String,
  val assignmentDigest: String,
  val counters: ReviewAccountingCounters = ReviewAccountingCounters(),
  val usage: ProviderTokenUsage = ProviderTokenUsage(),
  val terminalOutcome: String = "completed",
  val children: List<ReviewAccountingInput> = emptyList(),
) {
  init {
    require(lane.isNotBlank() && assignmentDigest.isNotBlank())
  }
}

data class ReviewAccountingNode(
  val lane: String,
  val assignmentDigest: String,
  val counters: ReviewAccountingCounters,
  /** The provider report exactly as observed, including its ownership declaration. */
  val providerUsage: ProviderTokenUsage,
  val directUsage: ProviderTokenUsage,
  val inclusiveUsage: ProviderTokenUsage,
  val terminalOutcome: String,
  val children: List<ReviewAccountingNode>,
)

data class ReviewAccountingSummary(
  val reviewId: String,
  val packetDigest: String,
  val parent: ReviewAccountingNode,
  val lanes: List<ReviewAccountingNode>,
  val aggregateDirectUsage: ProviderTokenUsage,
  val aggregateInclusiveUsage: ProviderTokenUsage,
  val budgetRegression: Boolean,
)

object ReviewTreeAccounting {
  fun summarize(reviewId: String, packetDigest: String, root: ReviewAccountingInput): ReviewAccountingSummary {
    require(reviewId.isNotBlank() && packetDigest.isNotBlank())
    val parent = fold(root)
    val lanes = flatten(parent.children)
    return ReviewAccountingSummary(
      reviewId,
      packetDigest,
      parent,
      lanes,
      flatten(listOf(parent)).map { it.directUsage }.sum(TokenOwnership.DIRECT),
      parent.inclusiveUsage,
      flatten(listOf(parent)).any { it.terminalOutcome == "budget_regression" },
    )
  }

  private fun fold(input: ReviewAccountingInput): ReviewAccountingNode {
    val children = input.children.sortedBy { it.lane }.map(::fold)
    val direct = if (input.usage.ownership == TokenOwnership.DIRECT) {
      input.usage
    } else {
      ProviderTokenUsage(ownership = TokenOwnership.DIRECT)
    }
    val inclusive = if (input.usage.ownership == TokenOwnership.INCLUSIVE) {
      input.usage
    } else {
      (listOf(direct) + children.map { it.inclusiveUsage }).sum(TokenOwnership.INCLUSIVE)
    }
    return ReviewAccountingNode(
      input.lane,
      input.assignmentDigest,
      input.counters,
      input.usage,
      direct,
      inclusive,
      input.terminalOutcome,
      children,
    )
  }

  private fun flatten(nodes: List<ReviewAccountingNode>): List<ReviewAccountingNode> =
    nodes.flatMap { listOf(it) + flatten(it.children) }
}

private fun List<ProviderTokenUsage>.sum(ownership: TokenOwnership): ProviderTokenUsage {
  fun total(value: (ProviderTokenUsage) -> Long?): Long? = mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()
  return ProviderTokenUsage(
    inputTokens = total(ProviderTokenUsage::inputTokens),
    cachedInputTokens = total(ProviderTokenUsage::cachedInputTokens),
    outputTokens = total(ProviderTokenUsage::outputTokens),
    reasoningTokens = total(ProviderTokenUsage::reasoningTokens),
    totalTokens = total(ProviderTokenUsage::totalTokens),
    ownership = ownership,
  )
}
