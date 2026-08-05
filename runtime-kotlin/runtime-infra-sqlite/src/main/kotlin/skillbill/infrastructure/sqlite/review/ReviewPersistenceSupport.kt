package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSummary
import java.sql.Connection

fun upsertReviewAccounting(connection: Connection, record: ReviewAccountingRecord) {
  connection.prepareStatement(
    """
    INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
    ON CONFLICT(review_id) DO UPDATE SET
      packet_digest = excluded.packet_digest,
      bounded_payload_json = excluded.bounded_payload_json,
      updated_at = CURRENT_TIMESTAMP
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, record.reviewId)
    statement.setString(PARAM_TWO, record.packetDigest)
    statement.setString(PARAM_THREE, JsonSupport.mapToJsonString(record.boundedPayload))
    statement.executeUpdate()
  }
}

fun loadReviewAccounting(connection: Connection, reviewId: String): ReviewAccountingRecord? =
  connection.prepareStatement(
    "SELECT packet_digest, bounded_payload_json FROM review_accounting WHERE review_id = ?",
  ).use { statement ->
    statement.setString(1, reviewId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) return@use null
      ReviewAccountingRecord(
        reviewId,
        rows.getString("packet_digest"),
        requireNotNull(decodeBoundedAccounting(rows.getString("bounded_payload_json"))) {
          "Malformed bounded review accounting for '$reviewId'."
        },
      )
    }
  }

// The bounded-payload contract is expressed in plain Kotlin values, so a stored row is decoded all
// the way down before it reaches the record's validator.
private fun decodeBoundedAccounting(rawJson: String): Map<String, Any?>? = JsonSupport.parseObjectOrNull(rawJson)?.let {
  JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
}

fun existingReviewSummary(connection: Connection, reviewRunId: String): ReviewSummary? =
  connection.prepareStatement(reviewSummarySql).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (resultSet.next()) resultSet.toReviewSummary() else null
    }
  }

fun reviewSummaryChanged(
  existingReviewSummary: ReviewSummary?,
  review: ImportedReview,
  existingFindings: List<ImportedFinding>,
): Boolean = existingReviewSummary == null ||
  existingReviewSummary.reviewSessionId != review.reviewSessionId ||
  existingReviewSummary.routedSkill != review.routedSkill ||
  existingReviewSummary.detectedScope != review.detectedScope ||
  existingReviewSummary.detectedStack != review.detectedStack ||
  existingReviewSummary.executionMode != review.executionMode ||
  existingReviewSummary.routedSkillCanonical != review.routedSkillCanonical ||
  existingReviewSummary.detectedStackCanonical != review.detectedStackCanonical ||
  existingReviewSummary.detectedScopeCanonical != review.detectedScopeCanonical ||
  existingReviewSummary.detectedScopeDetail != review.detectedScopeDetail ||
  existingReviewSummary.specialistReviewsRaw != review.specialistReviews.joinToString(",") ||
  existingFindings != review.findings

fun upsertReviewRun(connection: Connection, review: ImportedReview, sourcePath: String?) {
  connection.prepareStatement(
    """
    INSERT INTO review_runs (
      review_run_id,
      review_session_id,
      routed_skill,
      detected_scope,
      detected_stack,
      execution_mode,
      routed_skill_canonical,
      detected_stack_canonical,
      detected_scope_canonical,
      detected_scope_detail,
      specialist_reviews,
      source_path,
      raw_text
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(review_run_id) DO UPDATE SET
      review_session_id = excluded.review_session_id,
      routed_skill = excluded.routed_skill,
      detected_scope = excluded.detected_scope,
      detected_stack = excluded.detected_stack,
      execution_mode = excluded.execution_mode,
      routed_skill_canonical = excluded.routed_skill_canonical,
      detected_stack_canonical = excluded.detected_stack_canonical,
      detected_scope_canonical = excluded.detected_scope_canonical,
      detected_scope_detail = excluded.detected_scope_detail,
      specialist_reviews = excluded.specialist_reviews,
      source_path = excluded.source_path,
      raw_text = excluded.raw_text
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, review.reviewRunId)
    statement.setString(PARAM_TWO, review.reviewSessionId)
    statement.setString(PARAM_THREE, review.routedSkill)
    statement.setString(PARAM_FOUR, review.detectedScope)
    statement.setString(PARAM_FIVE, review.detectedStack)
    statement.setString(PARAM_SIX, review.executionMode)
    statement.setString(PARAM_SEVEN, review.routedSkillCanonical)
    statement.setString(PARAM_EIGHT, review.detectedStackCanonical)
    statement.setString(PARAM_NINE, review.detectedScopeCanonical)
    statement.setString(PARAM_TEN, review.detectedScopeDetail)
    statement.setString(PARAM_ELEVEN, review.specialistReviews.joinToString(","))
    statement.setString(PARAM_TWELVE, sourcePath)
    statement.setString(PARAM_THIRTEEN, review.rawText)
    statement.executeUpdate()
  }
}

fun replaceFindings(connection: Connection, review: ImportedReview) {
  // The run's persisted plan lanes are the only source of a finding's pack and area: a finding
  // reports which lane produced it, never what that lane covers.
  val lanesByName = review.planLanes.associateBy { it.laneSkillName }
  connection.prepareStatement("DELETE FROM findings WHERE review_run_id = ?").use { statement ->
    statement.setString(PARAM_ONE, review.reviewRunId)
    statement.executeUpdate()
  }
  review.findings.forEach { finding ->
    val lane = finding.laneSkillName?.let(lanesByName::get)
    connection.prepareStatement(
      """
      INSERT INTO findings (
        review_run_id,
        finding_id,
        severity,
        confidence,
        issue_category,
        location,
        description,
        finding_text,
        lane_skill_name,
        lane_area,
        lane_pack_slug
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, review.reviewRunId)
      statement.setString(PARAM_TWO, finding.findingId)
      statement.setString(PARAM_THREE, finding.severity)
      statement.setString(PARAM_FOUR, finding.confidence)
      statement.setString(PARAM_FIVE, finding.issueCategory)
      statement.setString(PARAM_SIX, finding.location)
      statement.setString(PARAM_SEVEN, finding.description)
      statement.setString(PARAM_EIGHT, finding.findingText)
      statement.setString(PARAM_NINE, finding.laneSkillName)
      statement.setString(PARAM_TEN, lane?.area)
      statement.setString(PARAM_ELEVEN, lane?.packSlug)
      statement.executeUpdate()
    }
  }
}

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
 * Whether a run's lane set changed the pack/area a finding resolves to. Findings are only rewritten
 * when something about them actually changed, because deleting a finding row cascades away its
 * recorded dispositions.
 */
fun findingLaneAttributionChanged(existingLanes: List<ReviewRunLane>, lanes: List<ReviewRunLane>): Boolean =
  existingLanes.associate { it.laneSkillName to (it.packSlug to it.area) } !=
    lanes.associate { it.laneSkillName to (it.packSlug to it.area) }

/** Bucket for a finding whose producing lane was never recorded; never silently dropped. */
const val UNATTRIBUTED_LANE: String = "unattributed"
private const val UNRESOLVED_ROUTED_SKILL: String = "unresolved"

fun java.sql.ResultSet.toImportedFinding(): ImportedFinding = ImportedFinding(
  findingId = getString("finding_id"),
  severity = getString("severity"),
  confidence = getString("confidence"),
  issueCategory = getString("issue_category"),
  location = getString("location"),
  description = getString("description"),
  findingText = getString("finding_text"),
  laneSkillName = getString("lane_skill_name"),
)

fun java.sql.ResultSet.toReviewSummary(): ReviewSummary = ReviewSummary(
  reviewRunId = getString("review_run_id"),
  reviewSessionId = getString("review_session_id"),
  routedSkill = getString("routed_skill"),
  detectedScope = getString("detected_scope"),
  detectedStack = getString("detected_stack"),
  executionMode = getString("execution_mode"),
  specialistReviewsRaw = getString("specialist_reviews"),
  reviewFinishedAt = getString("review_finished_at"),
  reviewFinishedEventEmittedAt = getString("review_finished_event_emitted_at"),
  orchestratedRun = getBoolean("orchestrated_run"),
  routedSkillCanonical = getString("routed_skill_canonical") ?: "unresolved",
  detectedStackCanonical = getString("detected_stack_canonical") ?: "unresolved",
  detectedScopeCanonical = getString("detected_scope_canonical") ?: "unresolved",
  detectedScopeDetail = getString("detected_scope_detail"),
)

fun java.sql.ResultSet.toNumberedFinding(number: Int): NumberedFinding = NumberedFinding(
  number = number,
  findingId = getString("finding_id"),
  severity = getString("severity"),
  confidence = getString("confidence"),
  location = getString("location"),
  description = getString("description"),
)
