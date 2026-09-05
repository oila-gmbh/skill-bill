package skillbill.ports.subtaskreview

import skillbill.contracts.JsonCodec
import skillbill.goalrunner.model.UNADDRESSED_FINDING_DEFAULT_CATEGORY
import skillbill.goalrunner.model.UNADDRESSED_FINDING_DEFAULT_SEVERITY
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.normalizedUnaddressedFindingCategory
import skillbill.goalrunner.model.normalizedUnaddressedFindingSeverity
import skillbill.review.model.ReviewFindingVerdict
import skillbill.text.Utf8Text

object GoalSubtaskReviewVerificationRejection {
  internal const val REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES: Int = 280

  internal fun rejectedVerificationFindings(
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
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS) as? List<*>
      ?: return emptyList()
    return dispositionsRaw.mapIndexedNotNull { index, entry ->
      rejectedVerificationFinding(
        RejectedVerificationFindingInput(
          entry = entry,
          index = index,
          reviewRunId = reviewRunId,
          reviewFindings = reviewFindings,
          reviewById = reviewById,
          scope = scope,
          truncationRecords = truncationRecords,
        ),
      )
    }
  }

  private fun rejectedVerificationFinding(input: RejectedVerificationFindingInput): UnaddressedFinding? {
    val map = JsonCodec.anyToStringAnyMap(input.entry) ?: return null
    val disposition = (map["disposition"] as? String)?.trim()?.lowercase()
    if (disposition != UNADDRESSED_FINDING_REJECTED_DISPOSITION) return null
    val findingId = (map["finding_id"] as? String)?.takeIf(String::isNotBlank) ?: return null
    val reviewFinding = input.reviewById[findingId]
    val existingOrdinal = reviewFinding?.let {
      input.reviewFindings.indexOfFirst { candidate -> candidate.findingId == findingId }
        .takeIf { it >= 0 }?.plus(1)
    }
    val verificationReason = verificationReason(map, findingId, input.truncationRecords)
    val severity = reviewFinding?.severity ?: UNADDRESSED_FINDING_DEFAULT_SEVERITY
    return UnaddressedFinding(
      issueKey = input.scope.issueKey,
      subtaskId = input.scope.subtaskId,
      workflowId = input.scope.workflowId,
      reviewPassNumber = input.scope.reviewPassNumber,
      findingOrdinal = existingOrdinal ?: (input.index + 1),
      severity = normalizedUnaddressedFindingSeverity(severity),
      issueCategory = normalizedUnaddressedFindingCategory(
        reviewFinding?.issueCategory ?: UNADDRESSED_FINDING_DEFAULT_CATEGORY,
      ),
      location = reviewFinding?.location ?: "<unknown>",
      summary = reviewFinding?.message ?: "<unknown>",
      reviewRunId = input.reviewRunId,
      findingId = findingId,
      claimVerdict = reviewFinding?.claimVerdict,
      scopeDisposition = reviewFinding?.scopeDisposition,
      citations = reviewFinding?.citations.orEmpty(),
      severityAdjustment = reviewFinding?.severityAdjustment,
      verificationDisposition = UNADDRESSED_FINDING_REJECTED_DISPOSITION,
      verificationReason = verificationReason,
    )
  }

  private fun verificationReason(
    map: Map<String, Any?>,
    findingId: String,
    truncationRecords: MutableList<String>?,
  ): String? = (map["reason"] as? String)?.takeIf(String::isNotBlank)?.let { rawReason ->
    val truncated = Utf8Text.truncateToUtf8Bytes(rawReason, REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES)
    if (Utf8Text.utf8Size(truncated) < Utf8Text.utf8Size(rawReason)) {
      truncationRecords?.add(rejectedVerificationReasonTruncationRecord(findingId))
    }
    truncated
  }

  internal fun rejectedVerificationReasonTruncationRecord(findingId: String): String =
    "seam=GoalSubtaskReviewVerificationRejection.rejectedVerificationFindings " +
      "value_used='verification_reason for $findingId truncated to $REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES " +
      "UTF-8 bytes' value_expected='verification_reason within $REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES " +
      "UTF-8 bytes' cause=agent census reason exceeded the ledger column byte cap"
}
