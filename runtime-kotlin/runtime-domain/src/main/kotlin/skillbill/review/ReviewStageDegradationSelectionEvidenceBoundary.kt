package skillbill.review

import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.review.model.ReviewStageDegradationReason

internal fun evidenceBoundaryUnboundRecord(
  reviewRunId: String,
  accounting: ReviewEvidenceBoundaryAccounting,
): ReviewStageDegradationMeasurement? {
  val seam = accounting.unboundSeam ?: return null
  return ReviewStageDegradationMeasurement(
    reviewRunId = reviewRunId,
    seam = seam,
    expected = "bound",
    actual = "unbound",
    reason = ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER,
  )
}

internal fun evidenceBoundaryUnexercisedRecord(
  reviewRunId: String,
  accounting: ReviewEvidenceBoundaryAccounting,
): ReviewStageDegradationMeasurement? {
  if (
    accounting.unboundSeam != null ||
    accounting.governedLaunchCount <= 0 ||
    accounting.authorizedReadCount != 0
  ) {
    return null
  }
  return ReviewStageDegradationMeasurement(
    reviewRunId = reviewRunId,
    seam = ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
    expected = "authorized_reads>0",
    actual = "authorized_reads=0",
    reason = ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED,
  )
}

internal fun evidenceBoundaryRefusedRecord(
  reviewRunId: String,
  accounting: ReviewEvidenceBoundaryAccounting,
): ReviewStageDegradationMeasurement? {
  if (accounting.refusedOperationCount <= 0) {
    return null
  }
  val categorySuffix = accounting.refusedCategories
    .groupingBy { it }
    .eachCount()
    .entries
    .sortedBy { it.key }
    .joinToString(",", prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
    .takeIf { accounting.refusedCategories.isNotEmpty() }
    .orEmpty()
  return ReviewStageDegradationMeasurement(
    reviewRunId = reviewRunId,
    seam = ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
    expected = "refused_operations=0",
    actual = "refused_operations=${accounting.refusedOperationCount}$categorySuffix",
    reason = ReviewStageDegradationReason.EVIDENCE_BOUNDARY_OPERATION_REFUSED,
  )
}

internal fun evidenceBoundaryRejectedRecord(
  reviewRunId: String,
  accounting: ReviewEvidenceBoundaryAccounting,
): ReviewStageDegradationMeasurement? {
  if (accounting.rejectedCandidateCount <= 0) {
    return null
  }
  return ReviewStageDegradationMeasurement(
    reviewRunId = reviewRunId,
    seam = "review.register.parse",
    expected = "rejected_candidates=0",
    actual = "rejected_candidates=${accounting.rejectedCandidateCount}",
    reason = ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED,
  )
}
