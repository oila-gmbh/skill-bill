package skillbill.review.context

import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingNode
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.TokenOwnership

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
