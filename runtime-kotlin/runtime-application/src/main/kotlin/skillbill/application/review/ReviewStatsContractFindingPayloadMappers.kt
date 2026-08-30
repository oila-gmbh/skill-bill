package skillbill.application.review

import skillbill.review.model.ReviewFindingDetail
import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewHealthStats

internal fun ReviewFindingStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "total_findings" to totalFindings,
  "accepted_findings" to acceptedFindings,
  "rejected_findings" to rejectedFindings,
  "unresolved_findings" to unresolvedFindings,
  "accepted_rate" to acceptedRate,
  "rejected_rate" to rejectedRate,
  "latest_outcome_counts" to latestOutcomeCounts,
  "accepted_severity_counts" to acceptedSeverityCounts,
  "rejected_severity_counts" to rejectedSeverityCounts,
  "unresolved_severity_counts" to unresolvedSeverityCounts,
  "accepted_finding_details" to acceptedFindingDetails.map(ReviewFindingDetail::toPayload),
  "rejected_findings_with_notes" to rejectedFindingsWithNotes,
  "rejected_finding_details" to rejectedFindingDetails.map(ReviewFindingDetail::toPayload),
)

internal fun ReviewHealthStats.toPayload(): Map<String, Any?> = linkedMapOf(
  "total_review_payload_records" to totalReviewPayloadRecords,
  "included_review_payload_records" to includedReviewPayloadRecords,
  "standalone_review_payload_records" to standaloneReviewPayloadRecords,
  "embedded_review_payload_records" to embeddedReviewPayloadRecords,
  "malformed_review_payload_records" to malformedReviewPayloadRecords,
  "data_quality_debt_records" to dataQualityDebtRecords,
  "total_findings" to totalFindings,
  "average_findings" to averageFindings,
  "median_findings" to medianFindings,
  "p90_findings" to p90Findings,
  "accepted_findings" to acceptedFindings,
  "rejected_findings" to rejectedFindings,
  "unresolved_findings" to unresolvedFindings,
  "accepted_rate" to acceptedRate,
  "rejected_rate" to rejectedRate,
  "unresolved_rate" to unresolvedRate,
  "severity_counts" to severityCounts,
  "confidence_counts" to confidenceCounts,
  "latest_outcome_counts" to latestOutcomeCounts,
  "issue_category_counts" to issueCategoryCounts,
  "category_severity_counts" to categorySeverityCounts,
  "platform_counts" to platformCounts,
  "scope_counts" to scopeCounts,
  "source_counts" to sourceCounts,
)

internal fun ReviewFindingDetail.toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "finding_id" to findingId,
  "issue_category" to issueCategory,
  "severity" to severity,
  "confidence" to confidence,
  "location" to location,
  "description" to description,
  "outcome_type" to outcomeType,
).apply {
  if (note.isNotEmpty()) put("note", note)
}
