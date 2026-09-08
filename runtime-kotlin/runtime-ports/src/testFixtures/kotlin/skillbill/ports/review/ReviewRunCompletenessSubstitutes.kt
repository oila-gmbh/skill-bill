package skillbill.ports.review

import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewExecutionMode

object UnavailableReviewRunLaneCompletenessRepository : ReviewRunLaneCompletenessRepository {
  override fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>) = unavailableCompleteness()

  override fun fetchReviewRunLanes(runId: String): List<ReviewRunLane> = unavailableCompleteness()

  override fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>) = unavailableCompleteness()

  override fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow> = unavailableCompleteness()

  override fun ensureTerminalReviewState(runId: String, executionMode: ReviewExecutionMode?) = unavailableCompleteness()

  override fun recordIntegrationPass(runId: String, record: ReviewIntegrationPassRecord) = unavailableCompleteness()

  override fun fetchIntegrationPass(runId: String): ReviewIntegrationPassRecord? = unavailableCompleteness()
}

object UnavailableReviewRunStageCompletenessRepository : ReviewRunStageCompletenessRepository {
  override fun recordFindingVerdicts(runId: String, verdicts: List<ReviewFindingVerdict>) = unavailableCompleteness()

  override fun fetchFindingVerdicts(runId: String): List<ReviewFindingVerdict> = unavailableCompleteness()

  override fun recordReviewPassClaims(runId: String, findings: List<ParallelReviewMergedFinding>) =
    unavailableCompleteness()

  override fun fetchReviewPassClaims(runId: String): ReviewPassClaimSnapshot? = unavailableCompleteness()

  override fun recordStageBoundary(runId: String, boundary: ReviewStageBoundary) = unavailableCompleteness()

  override fun fetchStageBoundaries(runId: String): List<ReviewStageBoundary> = unavailableCompleteness()

  override fun recordSpecProjectionReference(runId: String, reference: ReviewSpecProjectionReference) =
    unavailableCompleteness()

  override fun fetchSpecProjectionReference(runId: String): ReviewSpecProjectionReference? = unavailableCompleteness()
}

object UnavailableReviewRunCompletenessRepository :
  ReviewRunCompletenessRepository,
  ReviewRunLaneCompletenessRepository by UnavailableReviewRunLaneCompletenessRepository,
  ReviewRunStageCompletenessRepository by UnavailableReviewRunStageCompletenessRepository

private fun unavailableCompleteness(): Nothing = error("Review run completeness persistence is unavailable.")
