package skillbill.ports.persistence

import skillbill.ports.persistence.model.ReviewIntegrationPassRecord
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary

interface ReviewRunLaneCompletenessRepository {
  fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>)

  fun fetchReviewRunLanes(runId: String): List<ReviewRunLane>

  fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>)

  fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow>

  fun ensureTerminalReviewState(runId: String, executionMode: String?)

  fun recordIntegrationPass(runId: String, record: ReviewIntegrationPassRecord)

  fun fetchIntegrationPass(runId: String): ReviewIntegrationPassRecord?
}

interface ReviewRunStageCompletenessRepository {
  fun recordFindingVerdicts(runId: String, verdicts: List<ReviewFindingVerdict>)

  fun fetchFindingVerdicts(runId: String): List<ReviewFindingVerdict>

  fun recordReviewPassClaims(runId: String, findings: List<ParallelReviewMergedFinding>)

  fun fetchReviewPassClaims(runId: String): ReviewPassClaimSnapshot?

  fun recordStageBoundary(runId: String, boundary: ReviewStageBoundary)

  fun fetchStageBoundaries(runId: String): List<ReviewStageBoundary>

  fun recordSpecProjectionReference(runId: String, reference: ReviewSpecProjectionReference)

  fun fetchSpecProjectionReference(runId: String): ReviewSpecProjectionReference?
}

interface ReviewRunCompletenessRepository :
  ReviewRunLaneCompletenessRepository,
  ReviewRunStageCompletenessRepository

object UnavailableReviewRunLaneCompletenessRepository : ReviewRunLaneCompletenessRepository {
  override fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>) = unavailableCompleteness()

  override fun fetchReviewRunLanes(runId: String): List<ReviewRunLane> = unavailableCompleteness()

  override fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>) = unavailableCompleteness()

  override fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow> = unavailableCompleteness()

  override fun ensureTerminalReviewState(runId: String, executionMode: String?) = unavailableCompleteness()

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
