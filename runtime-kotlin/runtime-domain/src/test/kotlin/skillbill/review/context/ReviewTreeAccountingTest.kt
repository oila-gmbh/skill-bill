package skillbill.review.context

import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewLaneSegmentAccounting
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewTreeAccountingTest {
  @Test fun `counters aggregate inclusively without double counting a session`() {
    val digest = "a".repeat(64)
    val root = ReviewAccountingInput(
      "parent",
      "root",
      counters = ReviewAccountingCounters(10, 1, 2, 1, 1, 1),
      children = listOf(
        ReviewAccountingInput(
          "architecture",
          "arch",
          counters = ReviewAccountingCounters(100, 200, 300, 2, 3, 4),
          bundleCompositionDigest = digest,
          segmentAccounting = listOf(ReviewLaneSegmentAccounting("segment", 128, 2, digest)),
          unreviewedSegmentIds = listOf("unreviewed"),
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
    val lane = summary.lanes.single { it.lane == "architecture" }
    assertEquals(digest, lane.bundleCompositionDigest)
    assertEquals(listOf("unreviewed"), lane.unreviewedSegmentIds)
    assertEquals("segment", lane.segmentAccounting.single().segmentId)
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
}
