package skillbill.infrastructure.sqlite.goal

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import java.sql.ResultSet

internal fun readUnaddressedFinding(rows: ResultSet): UnaddressedFinding = UnaddressedFinding(
  issueKey = rows.getString(SharedPayloadKeys.ISSUE_KEY),
  workflowId = rows.getString(SharedPayloadKeys.WORKFLOW_ID),
  subtaskId = rows.getInt("subtask_id"),
  reviewPassNumber = rows.getInt("review_pass_number"),
  findingOrdinal = rows.getInt("finding_ordinal"),
  severity = rows.getString("severity"),
  issueCategory = rows.getString(ReviewFindingPayloadKeys.ISSUE_CATEGORY),
  location = rows.getString("location"),
  summary = rows.getString(SharedPayloadKeys.SUMMARY),
  reviewRunId = rows.getString(ReviewVerificationSignalKeys.REVIEW_RUN_ID),
  findingId = rows.getString(ReviewFindingPayloadKeys.FINDING_ID),
  claimVerdict = rows.getString(ReviewFindingPayloadKeys.CLAIM_VERDICT)
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let(ReviewClaimVerdict::fromWire),
  scopeDisposition = rows.getString(ReviewFindingPayloadKeys.SCOPE_DISPOSITION)?.trim()?.takeIf(String::isNotBlank)
    ?.let(ReviewScopeDisposition::fromWire),
  citations = ReviewFindingCitation.decodeList(rows.getString(ReviewFindingPayloadKeys.CITATIONS)),
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
