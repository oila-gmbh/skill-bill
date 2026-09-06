package skillbill.ports.review

import ReviewRunId
import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary

object UnavailableReviewRunLaneCompletenessRepository : ReviewRunLaneCompletenessRepository {
  override fun replaceReviewRunLanes(runId: ReviewRunId, lanes: List<ReviewRunLane>) = unavailableCompleteness()

  override fun fetchReviewRunLanes(runId: ReviewRunId): List<ReviewRunLane> = unavailableCompleteness()

  override fun recordFindingLaneAttribution(runId: ReviewRunId, attribution: Map<String, String>) =
    unavailableCompleteness()

  override fun reviewLaneEffectiveness(runId: ReviewRunId?): List<ReviewLaneEffectivenessRow> =
    unavailableCompleteness()

  override fun ensureTerminalReviewState(runId: ReviewRunId, executionMode: String?) = unavailableCompleteness()

  override fun recordIntegrationPass(runId: ReviewRunId, record: ReviewIntegrationPassRecord) =
    unavailableCompleteness()

  override fun fetchIntegrationPass(runId: ReviewRunId): ReviewIntegrationPassRecord? = unavailableCompleteness()
}

object UnavailableReviewRunStageCompletenessRepository : ReviewRunStageCompletenessRepository {
  override fun recordFindingVerdicts(runId: ReviewRunId, verdicts: List<ReviewFindingVerdict>) =
    unavailableCompleteness()

  override fun fetchFindingVerdicts(runId: ReviewRunId): List<ReviewFindingVerdict> = unavailableCompleteness()

  override fun recordReviewPassClaims(runId: ReviewRunId, findings: List<ParallelReviewMergedFinding>) =
    unavailableCompleteness()

  override fun fetchReviewPassClaims(runId: ReviewRunId): ReviewPassClaimSnapshot? = unavailableCompleteness()

  override fun recordStageBoundary(runId: ReviewRunId, boundary: ReviewStageBoundary) = unavailableCompleteness()

  override fun fetchStageBoundaries(runId: ReviewRunId): List<ReviewStageBoundary> = unavailableCompleteness()

  override fun recordSpecProjectionReference(runId: ReviewRunId, reference: ReviewSpecProjectionReference) =
    unavailableCompleteness()

  override fun fetchSpecProjectionReference(runId: ReviewRunId): ReviewSpecProjectionReference? =
    unavailableCompleteness()
}

object UnavailableReviewRunCompletenessRepository :
  ReviewRunCompletenessRepository,
  ReviewRunLaneCompletenessRepository by UnavailableReviewRunLaneCompletenessRepository,
  ReviewRunStageCompletenessRepository by UnavailableReviewRunStageCompletenessRepository

private fun unavailableCompleteness(): Nothing = error("Review run completeness persistence is unavailable.")
