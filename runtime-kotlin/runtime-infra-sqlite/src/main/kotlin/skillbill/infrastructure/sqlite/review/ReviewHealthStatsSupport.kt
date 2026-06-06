package skillbill.infrastructure.sqlite.review

import skillbill.review.model.ReviewHealthStats
import java.sql.Connection

val reviewHealthSeverities = listOf("Blocker", "Major", "Minor")
val reviewHealthConfidences = listOf("High", "Medium", "Low")
val reviewHealthScopes = listOf("branch_diff", "unstaged_changes", "working_tree", "unknown")

data class ReviewHealthPayload(
  val source: String,
  val payload: Map<String, Any?>,
)

fun buildReviewHealthStats(connection: Connection, reviewRunId: String?): ReviewHealthStats {
  val parsedPayloads = loadStandaloneReviewPayloads(connection) + loadEmbeddedReviewPayloads(connection)
  val scopedPayloads =
    if (reviewRunId == null) {
      parsedPayloads
    } else {
      parsedPayloads.filter { it.payload.stringHealthValue("review_run_id") == reviewRunId }
    }
  val malformedRecords = scopedPayloads.count { it.payload.isEmpty() }
  val includedPayloads = parsedPayloads
    .filter { it.payload.isNotEmpty() }
    .filter { reviewRunId == null || it.payload.stringHealthValue("review_run_id") == reviewRunId }
  val findingCounts = includedPayloads.map { it.payload.healthInt("total_findings") }
  val acceptedFindings = includedPayloads.sumOf { it.payload.healthInt("accepted_findings") }
  val rejectedFindings = includedPayloads.sumOf { it.payload.healthInt("rejected_findings") }
  val unresolvedFindings = includedPayloads.sumOf { it.payload.healthInt("unresolved_findings") }
  val totalFindings = acceptedFindings + rejectedFindings + unresolvedFindings
  return ReviewHealthStats(
    totalReviewPayloadRecords = scopedPayloads.size,
    includedReviewPayloadRecords = includedPayloads.size,
    standaloneReviewPayloadRecords = scopedPayloads.count { it.source == "standalone" },
    embeddedReviewPayloadRecords = scopedPayloads.count { it.source == "embedded" },
    malformedReviewPayloadRecords = malformedRecords,
    dataQualityDebtRecords = malformedRecords,
    totalFindings = totalFindings,
    averageFindings = average(findingCounts),
    medianFindings = median(findingCounts),
    p90Findings = p90(findingCounts),
    acceptedFindings = acceptedFindings,
    rejectedFindings = rejectedFindings,
    unresolvedFindings = unresolvedFindings,
    acceptedRate = rate(acceptedFindings, totalFindings),
    rejectedRate = rate(rejectedFindings, totalFindings),
    unresolvedRate = rate(unresolvedFindings, totalFindings),
    severityCounts = aggregateFindingDetailCounts(includedPayloads, "severity", reviewHealthSeverities),
    confidenceCounts = aggregateFindingDetailCounts(includedPayloads, "confidence", reviewHealthConfidences),
    latestOutcomeCounts = aggregateLatestOutcomeCounts(includedPayloads),
    issueCategoryCounts = aggregateFindingDetailCounts(includedPayloads, "issue_category", emptyList()),
    platformCounts = aggregatePayloadValueCounts(includedPayloads, "platform_slug", emptyList(), "unknown"),
    scopeCounts = aggregatePayloadValueCounts(includedPayloads, "scope_type", reviewHealthScopes, "unknown"),
    sourceCounts = countReviewHealthSources(includedPayloads, malformedRecords),
  )
}
