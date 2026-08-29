package skillbill.application.goalrunner

import skillbill.application.featuretask.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.UNADDRESSED_FINDING_DEFAULT_CATEGORY
import skillbill.goalrunner.model.UNADDRESSED_FINDING_DEFAULT_SEVERITY
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.normalizedUnaddressedFindingCategory
import skillbill.goalrunner.model.normalizedUnaddressedFindingSeverity
import skillbill.review.model.ReviewFindingVerdict
import skillbill.text.Utf8Text

internal object GoalSubtaskReviewVerificationRejection {
  internal const val REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES: Int = 280

  @Suppress("CyclomaticComplexMethod")
  fun rejectedVerificationFindings(
    verifyOutput: Map<String, Any?>,
    reviewOutput: Map<String, Any?>,
    scope: UnaddressedFindingLedgerScope,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
    truncationRecords: MutableList<String>? = null,
  ): List<UnaddressedFinding> {
    val reviewRunId = GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf(reviewOutput)
    val reviewFindings = GoalSubtaskReviewStructuredFindingsParse.structuredFindings(reviewOutput, recordedVerdicts)
    val reviewById = reviewFindings.associateBy { it.findingId.orEmpty() }
    val dispositionsRaw = verifyOutput["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS) as? List<*>
      ?: return emptyList()
    return dispositionsRaw.mapIndexedNotNull { index, entry ->
      val map = JsonSupport.anyToStringAnyMap(entry) ?: return@mapIndexedNotNull null
      val disposition = (map["disposition"] as? String)?.trim()?.lowercase()
      if (disposition != UNADDRESSED_FINDING_REJECTED_DISPOSITION) return@mapIndexedNotNull null
      val findingId = (map["finding_id"] as? String)?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
      val reviewFinding = reviewById[findingId]
      val existingOrdinal = reviewFinding?.let {
        reviewFindings.indexOfFirst { candidate ->
          candidate.findingId == findingId
        }.takeIf { it >= 0 }?.plus(1)
      }
      val verificationReason = (map["reason"] as? String)?.takeIf(String::isNotBlank)?.let { rawReason ->
        val truncated = Utf8Text.truncateToUtf8Bytes(rawReason, REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES)
        if (Utf8Text.utf8Size(truncated) < Utf8Text.utf8Size(rawReason)) {
          truncationRecords?.add(rejectedVerificationReasonTruncationRecord(findingId))
        }
        truncated
      }
      val severity = reviewFinding?.severity ?: UNADDRESSED_FINDING_DEFAULT_SEVERITY
      UnaddressedFinding(
        issueKey = scope.issueKey,
        subtaskId = scope.subtaskId,
        workflowId = scope.workflowId,
        reviewPassNumber = scope.reviewPassNumber,
        findingOrdinal = existingOrdinal ?: (index + 1),
        severity = normalizedUnaddressedFindingSeverity(severity),
        issueCategory = normalizedUnaddressedFindingCategory(
          reviewFinding?.issueCategory ?: UNADDRESSED_FINDING_DEFAULT_CATEGORY,
        ),
        location = reviewFinding?.location ?: "<unknown>",
        summary = reviewFinding?.message ?: "<unknown>",
        reviewRunId = reviewRunId,
        findingId = findingId,
        claimVerdict = reviewFinding?.claimVerdict,
        scopeDisposition = reviewFinding?.scopeDisposition,
        citations = reviewFinding?.citations.orEmpty(),
        severityAdjustment = reviewFinding?.severityAdjustment,
        verificationDisposition = UNADDRESSED_FINDING_REJECTED_DISPOSITION,
        verificationReason = verificationReason,
      )
    }
  }

  internal fun rejectedVerificationReasonTruncationRecord(findingId: String): String =
    "seam=GoalSubtaskReviewVerificationRejection.rejectedVerificationFindings " +
      "value_used='verification_reason for $findingId truncated to $REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES " +
      "UTF-8 bytes' value_expected='verification_reason within $REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES " +
      "UTF-8 bytes' cause=agent census reason exceeded the ledger column byte cap"
}
