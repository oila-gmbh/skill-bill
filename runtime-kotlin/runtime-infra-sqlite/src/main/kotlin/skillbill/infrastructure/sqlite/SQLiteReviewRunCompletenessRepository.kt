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
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary
import java.sql.Connection

class SQLiteReviewRunLaneCompletenessRepository(
  private val connection: Connection,
) : ReviewRunLaneCompletenessRepository {
  override fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>) =
    replaceReviewRunLanes(connection, runId, lanes)

  override fun fetchReviewRunLanes(runId: String): List<ReviewRunLane> = fetchReviewRunLanes(connection, runId)

  override fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>) =
    recordFindingLaneAttribution(connection, runId, attribution)

  override fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow> =
    queryReviewLaneEffectiveness(connection, runId)

  override fun ensureTerminalReviewState(runId: String, executionMode: String?) =
    ensureTerminalReviewState(connection, runId, executionMode)

  override fun recordIntegrationPass(runId: String, record: ReviewIntegrationPassRecord) =
    recordIntegrationPass(connection, runId, record)

  override fun fetchIntegrationPass(runId: String): ReviewIntegrationPassRecord? =
    fetchIntegrationPass(connection, runId)
}

class SQLiteReviewRunStageCompletenessRepository(
  private val connection: Connection,
) : ReviewRunStageCompletenessRepository {
  override fun recordFindingVerdicts(runId: String, verdicts: List<ReviewFindingVerdict>) =
    recordFindingVerdicts(connection, runId, verdicts)

  override fun fetchFindingVerdicts(runId: String): List<ReviewFindingVerdict> = fetchFindingVerdicts(connection, runId)

  override fun recordReviewPassClaims(runId: String, findings: List<ParallelReviewMergedFinding>) =
    recordReviewPassClaims(connection, runId, findings)

  override fun fetchReviewPassClaims(runId: String): ReviewPassClaimSnapshot? = fetchReviewPassClaims(connection, runId)

  override fun recordStageBoundary(runId: String, boundary: ReviewStageBoundary) =
    recordStageBoundary(connection, runId, boundary)

  override fun fetchStageBoundaries(runId: String): List<ReviewStageBoundary> = fetchStageBoundaries(connection, runId)

  override fun recordSpecProjectionReference(runId: String, reference: ReviewSpecProjectionReference) =
    recordSpecProjectionReference(connection, runId, reference)

  override fun fetchSpecProjectionReference(runId: String): ReviewSpecProjectionReference? =
    fetchSpecProjectionReference(connection, runId)
}

class SQLiteReviewRunCompletenessRepository(
  connection: Connection,
) : ReviewRunCompletenessRepository,
  ReviewRunLaneCompletenessRepository by SQLiteReviewRunLaneCompletenessRepository(connection),
  ReviewRunStageCompletenessRepository by SQLiteReviewRunStageCompletenessRepository(connection)
