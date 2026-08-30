package skillbill.review

import skillbill.error.ReviewAggregationIntegrityError
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewLaneAggregationInput

object ReviewLaneAggregation {
  fun requireCompleteLaneResults(
    expectedLanes: Collection<String>,
    results: List<ReviewLaneAggregationInput>,
    commitSequenceDigest: String,
  ): ReviewCoverageReport {
    val integrityFailure = laneIntegrityFailure(expectedLanes, results, commitSequenceDigest)
    if (integrityFailure != null) throw integrityFailure
    return ReviewCoverageReport(
      cleanLanes = results.filter { it.disposition == ReviewLaneReviewDisposition.COMPLETE }
        .map { it.lane }.sorted(),
      incompleteLanes = results.filter { it.disposition == ReviewLaneReviewDisposition.INCOMPLETE },
      integrationCompleted = false,
    )
  }

  private fun laneIntegrityFailure(
    expectedLanes: Collection<String>,
    results: List<ReviewLaneAggregationInput>,
    commitSequenceDigest: String,
  ): ReviewAggregationIntegrityError? {
    val duplicates = results.groupBy { it.lane }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
      return ReviewAggregationIntegrityError("a lane reported more than one result", duplicates.toList())
    }
    val byLane = results.associateBy { it.lane }
    val missing = expectedLanes.filterNot { it in byLane }
    if (missing.isNotEmpty()) {
      return ReviewAggregationIntegrityError("a selected lane produced no result", missing)
    }
    val foreign = results.map { it.lane }.filterNot { it in expectedLanes }
    if (foreign.isNotEmpty()) {
      return ReviewAggregationIntegrityError("a result names a lane that was never selected", foreign)
    }
    val mismatched = results.filter { it.commitSequenceDigest != commitSequenceDigest }.map { it.lane }
    return when {
      mismatched.isNotEmpty() -> ReviewAggregationIntegrityError(
        "a result was minted against a different commit sequence than the one under review",
        mismatched,
      )
      else -> null
    }
  }
}
