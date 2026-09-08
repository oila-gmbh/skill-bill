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

interface ReviewRunLaneCompletenessRepository {
  fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>)

  fun fetchReviewRunLanes(runId: String): List<ReviewRunLane>

  fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>)

  fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow>

  fun ensureTerminalReviewState(runId: String, executionMode: ReviewExecutionMode?)

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
