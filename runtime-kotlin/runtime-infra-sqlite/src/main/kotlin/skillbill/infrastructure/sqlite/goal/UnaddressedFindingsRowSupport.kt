package skillbill.infrastructure.sqlite.goal

import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import java.sql.Connection
import java.sql.ResultSet

internal fun readUnaddressedFinding(rows: ResultSet): UnaddressedFinding = UnaddressedFinding(
  issueKey = rows.getString("issue_key"),
  workflowId = rows.getString("workflow_id"),
  subtaskId = rows.getInt("subtask_id"),
  reviewPassNumber = rows.getInt("review_pass_number"),
  findingOrdinal = rows.getInt("finding_ordinal"),
  severity = rows.getString("severity"),
  issueCategory = rows.getString("issue_category"),
  location = rows.getString("location"),
  summary = rows.getString("summary"),
  reviewRunId = rows.getString("review_run_id"),
  findingId = rows.getString("finding_id"),
  claimVerdict = rows.getString("claim_verdict")?.trim()?.takeIf(String::isNotBlank)?.let(ReviewClaimVerdict::fromWire),
  scopeDisposition = rows.getString("scope_disposition")?.trim()?.takeIf(String::isNotBlank)
    ?.let(ReviewScopeDisposition::fromWire),
  citations = ReviewFindingCitation.decodeList(rows.getString("citations")),
  severityAdjustment = severityAdjustment(
    rows.getString("severity_adjustment_direction"),
    rows.getString("severity_adjustment_justification"),
  ),
  verificationDisposition = rows.getString("verification_disposition"),
  verificationReason = rows.getString("verification_reason"),
)

private fun severityAdjustment(direction: String?, justification: String?): ReviewSeverityAdjustment? {
  val parsedDirection = direction?.trim()?.takeIf(String::isNotBlank)?.let(ReviewSeverityAdjustmentDirection::fromWire)
    ?: return null
  val parsedJustification = justification?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewSeverityAdjustment(parsedDirection, parsedJustification)
}
