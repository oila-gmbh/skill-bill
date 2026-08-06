package skillbill.review

import skillbill.error.ReviewAggregationIntegrityError
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewLaneAggregationInput

object ReviewLaneAggregation {
  /**
   * Rejects a lane set aggregation cannot merge honestly. Every check fails loudly and names the
   * offending lanes: a silently dropped or doubled lane result is indistinguishable downstream from
   * a lane that genuinely found nothing.
   */
  @Suppress("ThrowsCount") // each breach is a distinct loud gate; folding them would blur the reason
  fun requireCompleteLaneResults(
    expectedLanes: Collection<String>,
    results: List<ReviewLaneAggregationInput>,
    commitSequenceDigest: String,
  ): ReviewCoverageReport {
    val duplicates = results.groupBy { it.lane }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
      throw ReviewAggregationIntegrityError("a lane reported more than one result", duplicates.toList())
    }
    val byLane = results.associateBy { it.lane }
    val missing = expectedLanes.filterNot { it in byLane }
    if (missing.isNotEmpty()) {
      throw ReviewAggregationIntegrityError("a selected lane produced no result", missing)
    }
    val foreign = results.map { it.lane }.filterNot { it in expectedLanes }
    if (foreign.isNotEmpty()) {
      throw ReviewAggregationIntegrityError("a result names a lane that was never selected", foreign)
    }
    val mismatched = results.filter { it.commitSequenceDigest != commitSequenceDigest }.map { it.lane }
    if (mismatched.isNotEmpty()) {
      throw ReviewAggregationIntegrityError(
        "a result was minted against a different commit sequence than the one under review",
        mismatched,
      )
    }
    return ReviewCoverageReport(
      cleanLanes = results.filter { it.disposition == ReviewLaneReviewDisposition.COMPLETE }
        .map { it.lane }.sorted(),
      incompleteLanes = results.filter { it.disposition == ReviewLaneReviewDisposition.INCOMPLETE },
      integrationCompleted = false,
    )
  }
}
