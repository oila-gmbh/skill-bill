package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.learnings.model.LearningScope
import skillbill.review.model.FindingOutcomeRow
import skillbill.review.model.ReviewFindingDetail
import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewFinishedFindingStats
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.ReviewLearningEntry
import skillbill.review.model.ReviewLearningsSummary
import skillbill.review.model.ReviewSummary
import skillbill.review.normalizeRoutedSkill
import skillbill.review.normalizeScopeType
import skillbill.review.normalizeStackLabel
import java.sql.Connection

fun reviewFinishedPayload(
  connection: Connection,
  reviewSummary: ReviewSummary,
  findingRows: List<FindingOutcomeRow>,
  level: String,
  routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
): ReviewFinishedTelemetry {
  val stats = filterReviewFinishedSummary(summarizeFindingRows(findingRows), level)
  val learningsSection = buildLearningsSection(connection, reviewSummary.reviewSessionId.orEmpty(), level)
  val normalizedStack = normalizeStackLabel(reviewSummary.detectedStack)
  val normalizedRoutedSkill = normalizeRoutedSkill(reviewSummary.routedSkill)
  val normalizedPlatformSlug = reviewPlatformSlug(
    reviewSummary.detectedStack,
    normalizedRoutedSkill,
    routedSkillPlatformSlugs,
  )
  val detectedStackDetail = normalizedStack.detail?.takeIf { it != normalizedPlatformSlug }
  return ReviewFinishedTelemetry(
    findingStats = stats,
    reviewRunId = reviewSummary.reviewRunId,
    reviewSessionId = reviewSummary.reviewSessionId.orEmpty(),
    routedSkill = normalizedRoutedSkill,
    reviewSubskills = parseSpecialistReviews(reviewSummary.specialistReviewsRaw),
    reviewScope = normalizeReviewScope(reviewSummary.detectedScope),
    reviewPlatform = normalizedPlatformSlug,
    detectedStack = normalizedPlatformSlug,
    detectedStackDetail = detectedStackDetail,
    fallback = normalizedStack.fallback,
    fallbackReason = normalizedStack.fallbackReason,
    platformSlug = normalizedPlatformSlug,
    scopeType = normalizeScopeType(reviewSummary.detectedScope),
    executionMode = reviewSummary.executionMode,
    reviewFinishedAt = reviewSummary.reviewFinishedAt,
    learnings = learningsSection,
    reviewContextAccounting = loadReviewAccounting(connection, reviewSummary.reviewRunId)?.boundedPayload,
  )
}

fun filterReviewFinishedSummary(summary: ReviewFindingStats, level: String): ReviewFinishedFindingStats =
  ReviewFinishedFindingStats(
    totalFindings = summary.totalFindings,
    acceptedFindings = summary.acceptedFindings,
    rejectedFindings = summary.rejectedFindings,
    unresolvedFindings = summary.unresolvedFindings,
    acceptedRate = summary.acceptedRate,
    rejectedRate = summary.rejectedRate,
    acceptedFindingDetails = reviewFindingDetails(summary.acceptedFindingDetails, level == "full"),
    rejectedFindingDetails = reviewFindingDetails(summary.rejectedFindingDetails, level == "full"),
  )

fun buildLearningsSection(connection: Connection, reviewSessionId: String, level: String): ReviewLearningsSummary {
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
  return ReviewLearningsSummary(
    appliedCount = (learningsData?.get("applied_learning_count") as? Number)?.toInt() ?: 0,
    appliedReferences =
    (learningsData?.get("applied_learning_references") as? List<*>)
      ?.mapNotNull { it?.toString() }
      ?: emptyList(),
    appliedSummary = learningsData?.get("applied_learnings")?.toString() ?: "none",
    scopeCounts = scopeCounts,
    entries = learningsEntries,
  )
}

fun learningsEntries(entries: List<Map<String, Any?>>, includeText: Boolean): List<ReviewLearningEntry> =
  entries.map { entry ->
    if (includeText) {
      ReviewLearningEntry(
        reference = entry["reference"]?.toString(),
        scope = entry["scope"]?.toString(),
        title = entry["title"]?.toString(),
        ruleText = entry["rule_text"]?.toString(),
      )
    } else {
      ReviewLearningEntry(
        reference = entry["reference"]?.toString(),
        scope = entry["scope"]?.toString(),
      )
    }
  }

fun reviewFindingDetails(details: List<ReviewFindingDetail>, includeText: Boolean): List<ReviewFindingDetail> =
  details.map { detail ->
    if (includeText) {
      detail
    } else {
      detail.copy(
        location = "",
        description = "",
        note = "",
      )
    }
  }

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
