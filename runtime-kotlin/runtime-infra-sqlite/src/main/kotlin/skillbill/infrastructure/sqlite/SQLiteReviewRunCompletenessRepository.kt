package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.review.ensureTerminalReviewState
import skillbill.infrastructure.sqlite.review.fetchReviewRunLanes
import skillbill.infrastructure.sqlite.review.queryReviewLaneEffectiveness
import skillbill.infrastructure.sqlite.review.recordFindingLaneAttribution
import skillbill.infrastructure.sqlite.review.replaceReviewRunLanes
import skillbill.ports.persistence.ReviewRunCompletenessRepository
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewRunLane
import java.sql.Connection

class SQLiteReviewRunCompletenessRepository(
  private val connection: Connection,
) : ReviewRunCompletenessRepository {
  override fun replaceReviewRunLanes(runId: String, lanes: List<ReviewRunLane>) =
    replaceReviewRunLanes(connection, runId, lanes)

  override fun fetchReviewRunLanes(runId: String): List<ReviewRunLane> = fetchReviewRunLanes(connection, runId)

  override fun recordFindingLaneAttribution(runId: String, attribution: Map<String, String>) =
    recordFindingLaneAttribution(connection, runId, attribution)

  override fun reviewLaneEffectiveness(runId: String?): List<ReviewLaneEffectivenessRow> =
    queryReviewLaneEffectiveness(connection, runId)

  override fun ensureTerminalReviewState(runId: String, executionMode: String?) =
    ensureTerminalReviewState(connection, runId, executionMode)
}
