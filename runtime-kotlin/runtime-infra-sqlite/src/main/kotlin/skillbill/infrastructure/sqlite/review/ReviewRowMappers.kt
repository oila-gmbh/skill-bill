package skillbill.infrastructure.sqlite.review

import skillbill.review.model.ImportedFinding
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewSummary

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
  claimVerdict = getString("claim_verdict")?.trim()?.takeIf(String::isNotBlank)?.let(ReviewClaimVerdict::fromWire),
  scopeDisposition = getString("scope_disposition")?.trim()?.takeIf(String::isNotBlank)
    ?.let(ReviewScopeDisposition::fromWire),
  citations = ReviewFindingCitation.decodeList(getString("citations")),
  severityAdjustment = numberedFindingAdjustment(
    getString("severity_adjustment_direction"),
    getString("severity_adjustment_justification"),
  ),
)

private fun numberedFindingAdjustment(direction: String?, justification: String?): ReviewSeverityAdjustment? {
  val parsedDirection = direction?.trim()?.takeIf(String::isNotBlank)?.let(ReviewSeverityAdjustmentDirection::fromWire)
    ?: return null
  val parsedJustification = justification?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewSeverityAdjustment(parsedDirection, parsedJustification)
}
