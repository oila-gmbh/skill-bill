package skillbill.review.context

import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingNode
import skillbill.review.context.model.ReviewAccountingSummary

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
      parent.inclusiveCounters,
    )
  }

  private fun fold(input: ReviewAccountingInput): ReviewAccountingNode {
    val children = input.children.sortedBy { it.lane }.map(::fold)
    return ReviewAccountingNode(
      input.lane,
      input.assignmentDigest,
      input.counters,
      children.fold(input.counters) { total, child -> total + child.inclusiveCounters },
      input.terminalOutcome,
      input.bundleCompositionDigest,
      input.segmentAccounting,
      input.unreviewedSegmentIds,
      children,
    )
  }

  private fun flatten(nodes: List<ReviewAccountingNode>): List<ReviewAccountingNode> =
    nodes.flatMap { listOf(it) + flatten(it.children) }
}
