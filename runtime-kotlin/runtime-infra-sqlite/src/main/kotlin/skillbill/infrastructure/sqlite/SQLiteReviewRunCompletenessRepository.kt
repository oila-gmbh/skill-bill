package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.review.ensureTerminalReviewState
import skillbill.infrastructure.sqlite.review.fetchFindingVerdicts
import skillbill.infrastructure.sqlite.review.fetchIntegrationPass
import skillbill.infrastructure.sqlite.review.fetchReviewPassClaims
import skillbill.infrastructure.sqlite.review.fetchReviewRunLanes
import skillbill.infrastructure.sqlite.review.fetchSpecProjectionReference
import skillbill.infrastructure.sqlite.review.fetchStageBoundaries
import skillbill.infrastructure.sqlite.review.queryReviewLaneEffectiveness
import skillbill.infrastructure.sqlite.review.recordFindingLaneAttribution
import skillbill.infrastructure.sqlite.review.recordFindingVerdicts
import skillbill.infrastructure.sqlite.review.recordIntegrationPass
import skillbill.infrastructure.sqlite.review.recordReviewPassClaims
import skillbill.infrastructure.sqlite.review.recordSpecProjectionReference
import skillbill.infrastructure.sqlite.review.recordStageBoundary
import skillbill.infrastructure.sqlite.review.replaceReviewRunLanes
import skillbill.ports.review.ReviewRunCompletenessRepository
import skillbill.ports.review.ReviewRunLaneCompletenessRepository
import skillbill.ports.review.ReviewRunStageCompletenessRepository
import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunId
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary
import java.sql.Connection

class SQLiteReviewRunLaneCompletenessRepository(
  private val connection: Connection,
) : ReviewRunLaneCompletenessRepository {
  override fun replaceReviewRunLanes(runId: ReviewRunId, lanes: List<ReviewRunLane>) =
    replaceReviewRunLanes(connection, runId.value, lanes)

  override fun fetchReviewRunLanes(runId: ReviewRunId): List<ReviewRunLane> =
    fetchReviewRunLanes(connection, runId.value)

  override fun recordFindingLaneAttribution(runId: ReviewRunId, attribution: Map<String, String>) =
    recordFindingLaneAttribution(connection, runId.value, attribution)

  override fun reviewLaneEffectiveness(runId: ReviewRunId?): List<ReviewLaneEffectivenessRow> =
    queryReviewLaneEffectiveness(connection, runId?.value)

  override fun ensureTerminalReviewState(runId: ReviewRunId, executionMode: String?) =
    ensureTerminalReviewState(connection, runId.value, executionMode)

  override fun recordIntegrationPass(runId: ReviewRunId, record: ReviewIntegrationPassRecord) =
    recordIntegrationPass(connection, runId.value, record)

  override fun fetchIntegrationPass(runId: ReviewRunId): ReviewIntegrationPassRecord? =
    fetchIntegrationPass(connection, runId.value)
}

class SQLiteReviewRunStageCompletenessRepository(
  private val connection: Connection,
) : ReviewRunStageCompletenessRepository {
  override fun recordFindingVerdicts(runId: ReviewRunId, verdicts: List<ReviewFindingVerdict>) =
    recordFindingVerdicts(connection, runId.value, verdicts)

  override fun fetchFindingVerdicts(runId: ReviewRunId): List<ReviewFindingVerdict> =
    fetchFindingVerdicts(connection, runId.value)

  override fun recordReviewPassClaims(runId: ReviewRunId, findings: List<ParallelReviewMergedFinding>) =
    recordReviewPassClaims(connection, runId.value, findings)

  override fun fetchReviewPassClaims(runId: ReviewRunId): ReviewPassClaimSnapshot? =
    fetchReviewPassClaims(connection, runId.value)

  override fun recordStageBoundary(runId: ReviewRunId, boundary: ReviewStageBoundary) =
    recordStageBoundary(connection, runId.value, boundary)

  override fun fetchStageBoundaries(runId: ReviewRunId): List<ReviewStageBoundary> =
    fetchStageBoundaries(connection, runId.value)

  override fun recordSpecProjectionReference(runId: ReviewRunId, reference: ReviewSpecProjectionReference) =
    recordSpecProjectionReference(connection, runId.value, reference)

  override fun fetchSpecProjectionReference(runId: ReviewRunId): ReviewSpecProjectionReference? =
    fetchSpecProjectionReference(connection, runId.value)
}

class SQLiteReviewRunCompletenessRepository(
  connection: Connection,
) : ReviewRunCompletenessRepository,
  ReviewRunLaneCompletenessRepository by SQLiteReviewRunLaneCompletenessRepository(connection),
  ReviewRunStageCompletenessRepository by SQLiteReviewRunStageCompletenessRepository(connection)
