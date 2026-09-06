package skillbill.ports.review

import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunId
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary

interface ReviewRunLaneCompletenessRepository {
  fun replaceReviewRunLanes(runId: ReviewRunId, lanes: List<ReviewRunLane>)

  fun fetchReviewRunLanes(runId: ReviewRunId): List<ReviewRunLane>

  fun recordFindingLaneAttribution(runId: ReviewRunId, attribution: Map<String, String>)

  fun reviewLaneEffectiveness(runId: ReviewRunId?): List<ReviewLaneEffectivenessRow>

  fun ensureTerminalReviewState(runId: ReviewRunId, executionMode: String?)

  fun recordIntegrationPass(runId: ReviewRunId, record: ReviewIntegrationPassRecord)

  fun fetchIntegrationPass(runId: ReviewRunId): ReviewIntegrationPassRecord?
}

interface ReviewRunStageCompletenessRepository {
  fun recordFindingVerdicts(runId: ReviewRunId, verdicts: List<ReviewFindingVerdict>)

  fun fetchFindingVerdicts(runId: ReviewRunId): List<ReviewFindingVerdict>

  fun recordReviewPassClaims(runId: ReviewRunId, findings: List<ParallelReviewMergedFinding>)

  fun fetchReviewPassClaims(runId: ReviewRunId): ReviewPassClaimSnapshot?

  fun recordStageBoundary(runId: ReviewRunId, boundary: ReviewStageBoundary)

  fun fetchStageBoundaries(runId: ReviewRunId): List<ReviewStageBoundary>

  fun recordSpecProjectionReference(runId: ReviewRunId, reference: ReviewSpecProjectionReference)

  fun fetchSpecProjectionReference(runId: ReviewRunId): ReviewSpecProjectionReference?
}

interface ReviewRunCompletenessRepository :
  ReviewRunLaneCompletenessRepository,
  ReviewRunStageCompletenessRepository
