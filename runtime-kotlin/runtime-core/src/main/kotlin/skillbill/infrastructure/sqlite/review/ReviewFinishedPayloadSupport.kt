package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.learnings.LearningScope
import skillbill.review.FindingOutcomeRow
import skillbill.review.ReviewSummary
import java.sql.Connection

fun reviewFinishedPayload(
  connection: Connection,
  reviewSummary: ReviewSummary,
  findingRows: List<FindingOutcomeRow>,
  level: String,
): Map<String, Any?> {
  val payload = filterReviewFinishedSummary(summarizeFindingRows(findingRows), level)
  val learningsSection = buildLearningsSection(connection, reviewSummary.reviewSessionId.orEmpty(), level)
  return payload +
    mapOf(
      "review_session_id" to reviewSummary.reviewSessionId.orEmpty(),
      "routed_skill" to reviewSummary.routedSkill,
      "review_subskills" to parseSpecialistReviews(reviewSummary.specialistReviewsRaw),
      "review_scope" to normalizeReviewScope(reviewSummary.detectedScope),
      "review_platform" to reviewSummary.detectedStack,
      "execution_mode" to reviewSummary.executionMode,
      "review_finished_at" to reviewSummary.reviewFinishedAt,
      "learnings" to learningsSection,
    )
}

fun filterReviewFinishedSummary(summary: Map<String, Any?>, level: String): Map<String, Any?> {
  val filtered =
    summary
      .filterKeys {
        it !in setOf(
          "rejected_findings",
          "rejected_rate",
          "rejected_findings_with_notes",
          "latest_outcome_counts",
          "unresolved_severity_counts",
          "accepted_severity_counts",
          "rejected_severity_counts",
        )
      }.toMutableMap()
  filtered["accepted_finding_details"] = reviewFindingDetails(summary["accepted_finding_details"], level == "full")
  filtered["rejected_finding_details"] = reviewFindingDetails(summary["rejected_finding_details"], level == "full")
  return filtered
}

fun buildLearningsSection(connection: Connection, reviewSessionId: String, level: String): Map<String, Any?> {
  val defaultScopeCounts = LearningScope.emptyScopeCounts()
  val learningsData =
    if (reviewSessionId.isEmpty()) {
      null
    } else {
      fetchSessionLearnings(connection, reviewSessionId)
    }
  val learningsEntries =
    learningsEntries(
      entries = (learningsData?.get("learnings") as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList(),
      includeText = level == "full",
    )
  val scopeCounts =
    defaultScopeCounts + (
      (learningsData?.get("scope_counts") as? Map<*, *>)
        ?.filterKeys { it is String }
        ?.mapKeys { it.key as String }
        ?.mapValues { entry -> (entry.value as? Number)?.toInt() ?: 0 }
        ?: emptyMap()
      )
  return mapOf(
    "applied_count" to ((learningsData?.get("applied_learning_count") as? Number)?.toInt() ?: 0),
    "applied_references" to ((learningsData?.get("applied_learning_references") as? List<*>) ?: emptyList<Any?>()),
    "applied_summary" to (learningsData?.get("applied_learnings") ?: "none"),
    "scope_counts" to scopeCounts,
    "entries" to learningsEntries,
  )
}

fun learningsEntries(entries: List<Map<String, Any?>>, includeText: Boolean): List<Map<String, Any?>> =
  entries.map { entry ->
    if (includeText) {
      mapOf(
        "reference" to entry["reference"],
        "scope" to entry["scope"],
        "title" to entry["title"],
        "rule_text" to entry["rule_text"],
      ).filterValues { it != null }
    } else {
      mapOf(
        "reference" to entry["reference"],
        "scope" to entry["scope"],
      )
    }
  }

fun reviewFindingDetails(details: Any?, includeText: Boolean): List<Map<String, Any?>> =
  (details as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.map { detail ->
    if (includeText) {
      mapOf(
        "finding_id" to detail["finding_id"],
        "severity" to detail["severity"],
        "confidence" to detail["confidence"],
        "outcome_type" to detail["outcome_type"],
        "location" to detail["location"],
        "description" to detail["description"],
        "note" to detail["note"],
      ).filterValues { it != null && it != "" }
    } else {
      mapOf(
        "finding_id" to detail["finding_id"],
        "severity" to detail["severity"],
        "confidence" to detail["confidence"],
        "outcome_type" to detail["outcome_type"],
      )
    }
  } ?: emptyList()

fun parseSpecialistReviews(rawValue: String?): List<String> =
  rawValue.orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)

fun normalizeReviewScope(detectedScope: String?): String = detectedScope.orEmpty().substringBefore("(").trim()

private fun fetchSessionLearnings(connection: Connection, reviewSessionId: String): Map<String, Any?>? {
  val rawJson =
    connection.prepareStatement(
      """
    SELECT learnings_json
    FROM session_learnings
    WHERE review_session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, reviewSessionId)
      statement.executeQuery().use { resultSet ->
        if (resultSet.next()) {
          resultSet.getString("learnings_json")
        } else {
          null
        }
      }
    }
  return rawJson?.let(::decodeSessionLearnings)
}

private fun decodeSessionLearnings(rawJson: String): Map<String, Any?>? = JsonSupport.parseObjectOrNull(rawJson)?.let {
  JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
}
