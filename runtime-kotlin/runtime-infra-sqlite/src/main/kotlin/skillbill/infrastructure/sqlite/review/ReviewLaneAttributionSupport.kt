package skillbill.infrastructure.sqlite.review

import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewRunLane
import java.sql.Connection

/** Bucket for a finding whose producing lane was never recorded; never silently dropped. */
const val UNATTRIBUTED_LANE: String = "unattributed"
private const val UNRESOLVED_ROUTED_SKILL: String = "unresolved"

/**
 * Replaces a run's lane set. Delete-then-insert keyed by the run makes a re-import converge on one
 * row per lane instead of accumulating duplicates.
 */
fun replaceReviewRunLanes(connection: Connection, reviewRunId: String, lanes: List<ReviewRunLane>) {
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement("DELETE FROM review_run_lanes WHERE review_run_id = ?").use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeUpdate()
  }
  lanes.forEach { lane ->
    connection.prepareStatement(
      """
      INSERT INTO review_run_lanes (
        review_run_id,
        lane_skill_name,
        pack_slug,
        area,
        depth,
        required,
        order_index,
        origin_layer_chain,
        resolution_state
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.setString(PARAM_TWO, lane.laneSkillName)
      statement.setString(PARAM_THREE, lane.packSlug)
      statement.setString(PARAM_FOUR, lane.area)
      statement.setInt(PARAM_FIVE, lane.depth)
      statement.setBoolean(PARAM_SIX, lane.required)
      statement.setInt(PARAM_SEVEN, lane.orderIndex)
      statement.setString(PARAM_EIGHT, lane.originLayerChain.joinToString("->"))
      statement.setString(PARAM_NINE, lane.resolutionState)
      statement.executeUpdate()
    }
  }
}

// shortcut: the runtime records a run's launch plan before the review text is imported, so the
// parent row is reserved here to keep the lane foreign key honest; drop this once review runs gain
// a registration seam of their own. The import upsert overwrites every reserved placeholder field.
// Until then the empty raw_text is the marker that distinguishes a reservation from an imported
// review — see ReviewRuntime.reviewExists, which run-facing reads gate on.
private fun reserveReviewRun(connection: Connection, reviewRunId: String) {
  connection.prepareStatement(
    "INSERT OR IGNORE INTO review_runs (review_run_id, review_session_id, raw_text) VALUES (?, ?, '')",
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.setString(PARAM_TWO, reviewRunId)
    statement.executeUpdate()
  }
}

fun fetchReviewRunLanes(connection: Connection, reviewRunId: String): List<ReviewRunLane> =
  connection.prepareStatement(reviewRunLanesSql).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            ReviewRunLane(
              laneSkillName = resultSet.getString("lane_skill_name"),
              packSlug = resultSet.getString("pack_slug"),
              area = resultSet.getString("area"),
              depth = resultSet.getInt("depth"),
              required = resultSet.getBoolean("required"),
              orderIndex = resultSet.getInt("order_index"),
              originLayerChain = resultSet.getString("origin_layer_chain")
                .orEmpty()
                .split("->")
                .filter(String::isNotEmpty),
              resolutionState = resultSet.getString("resolution_state"),
            ),
          )
        }
      }
    }
  }

fun queryReviewLaneEffectiveness(connection: Connection, reviewRunId: String?): List<ReviewLaneEffectivenessRow> {
  val counters = linkedMapOf<Triple<String, String, String>, MutableList<String>>()
  connection.prepareStatement(laneEffectivenessSql).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.setString(PARAM_TWO, reviewRunId)
    statement.executeQuery().use { resultSet ->
      while (resultSet.next()) {
        val key = Triple(
          resultSet.getString("routed_skill_canonical") ?: UNRESOLVED_ROUTED_SKILL,
          resultSet.getString("pack_slug") ?: UNATTRIBUTED_LANE,
          resultSet.getString("area") ?: UNATTRIBUTED_LANE,
        )
        counters.getOrPut(key) { mutableListOf() } += resultSet.getString("outcome_type").orEmpty()
      }
    }
  }
  return counters.map { (key, outcomes) ->
    ReviewLaneEffectivenessRow(
      routedSkillCanonical = key.first,
      packSlug = key.second,
      area = key.third,
      totalFindings = outcomes.size,
      acceptedFindings = outcomes.count { it in acceptedFindingOutcomeTypes },
      rejectedFindings = outcomes.count { it in rejectedFindingOutcomeTypes },
    )
  }
}

/**
 * Corrects the lane columns of already-persisted finding rows in place, keyed by
 * (review_run_id, finding_id). An UPDATE rather than a delete-and-reinsert is what makes a lane
 * correction non-destructive: findings cascade-delete their feedback_events, so re-importing an
 * already-triaged run after the composed plan shifts would otherwise erase every disposition.
 */
fun updateFindingLaneAttribution(
  connection: Connection,
  review: ImportedReview,
  lanes: List<ReviewRunLane>,
  recordedLanes: Map<String, String>,
) {
  val lanesByName = lanes.associateBy { it.laneSkillName }
  connection.prepareStatement(
    """
    UPDATE findings SET lane_skill_name = ?, lane_area = ?, lane_pack_slug = ?
    WHERE review_run_id = ? AND finding_id = ?
    """.trimIndent(),
  ).use { statement ->
    review.findings.forEach { finding ->
      val laneName = finding.effectiveLaneName(recordedLanes)
      val lane = laneName?.let(lanesByName::get)
      statement.setString(PARAM_ONE, laneName)
      statement.setString(PARAM_TWO, lane?.area)
      statement.setString(PARAM_THREE, lane?.packSlug)
      statement.setString(PARAM_FOUR, review.reviewRunId)
      statement.setString(PARAM_FIVE, finding.findingId)
      statement.executeUpdate()
    }
  }
}

/**
 * Records finding-to-lane attribution straight from the runtime's own merge result, before the
 * review text exists. Insert-or-replace keyed by (run, finding) keeps a re-run idempotent.
 */
fun recordFindingLaneAttribution(connection: Connection, reviewRunId: String, attribution: Map<String, String>) {
  if (attribution.isEmpty()) return
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement(
    """
    INSERT INTO review_run_finding_lanes (review_run_id, finding_id, lane_skill_name)
    VALUES (?, ?, ?)
    ON CONFLICT(review_run_id, finding_id) DO UPDATE SET lane_skill_name = excluded.lane_skill_name
    """.trimIndent(),
  ).use { statement ->
    attribution.forEach { (findingId, laneSkillName) ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.setString(PARAM_TWO, findingId)
      statement.setString(PARAM_THREE, laneSkillName)
      statement.executeUpdate()
    }
  }
}

fun fetchFindingLaneAttribution(connection: Connection, reviewRunId: String): Map<String, String> =
  connection.prepareStatement(
    "SELECT finding_id, lane_skill_name FROM review_run_finding_lanes WHERE review_run_id = ?",
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          put(resultSet.getString("finding_id"), resultSet.getString("lane_skill_name"))
        }
      }
    }
  }

// A finding's producing lane, preferring what the runtime recorded from its own merge result over
// provenance parsed out of review text.
internal fun ImportedFinding.effectiveLaneName(recordedLanes: Map<String, String>): String? =
  recordedLanes[findingId] ?: laneSkillName
