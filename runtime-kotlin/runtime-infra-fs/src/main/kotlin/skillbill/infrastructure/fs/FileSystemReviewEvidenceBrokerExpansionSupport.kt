package skillbill.infrastructure.fs

import skillbill.contracts.JsonSupport
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewEvidenceLimits
import skillbill.review.context.model.ReviewExpansionRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val EXPANSION_ID_HEX_LENGTH = 24

private const val MAX_REFUSAL_RECORDS = 127
private const val REFUSAL_TRUNCATION_RESERVE_BYTES = 256

internal fun stableReviewExpansionId(assignmentDigest: String, path: String, reason: String): String {
  val input = "$assignmentDigest\u0000$path\u0000$reason".toByteArray(StandardCharsets.UTF_8)
  val digest = MessageDigest.getInstance("SHA-256").digest(input)
    .joinToString("") { "%02x".format(it) }
  return "exp-${digest.take(EXPANSION_ID_HEX_LENGTH)}"
}

internal fun reviewEvidenceBatchResult(
  results: List<ReviewEvidenceResult>,
  outcome: ReviewBudgetOutcome?,
  cumulativeBytes: Long,
  expansionLedger: List<ReviewExpansionRecord>,
  refusalLedger: MutableList<ReviewRefusedOperationRecord>,
): ReviewEvidenceBatchResult {
  results.forEach { result ->
    result.forbidden?.let { appendReviewRefusal(refusalLedger, ReviewRefusedOperationRecord(it.category, it.target)) }
    result.budgetExceeded?.let {
      appendReviewRefusal(
        refusalLedger,
        ReviewRefusedOperationRecord(
          it.type,
          it.budgetKind,
        ),
      )
    }
  }
  return ReviewEvidenceBatchResult(results, cumulativeBytes, expansionLedger, outcome)
}

internal fun appendReviewRefusal(
  ledger: MutableList<ReviewRefusedOperationRecord>,
  record: ReviewRefusedOperationRecord,
) {
  if (ledger.lastOrNull()?.category == "refusal_metadata_truncated") return
  val bytes = (ledger + record).sumOf {
    JsonSupport.mapToJsonString(
      mapOf(
        "category" to it.category,
        "target" to it.target,
      ),
    ).toByteArray(Charsets.UTF_8).size + 1
  }
  val maxBytes = ReviewEvidenceLimits.METADATA_BYTES - REFUSAL_TRUNCATION_RESERVE_BYTES
  if (ledger.size >= MAX_REFUSAL_RECORDS || bytes > maxBytes) {
    ledger += ReviewRefusedOperationRecord("refusal_metadata_truncated", "review-evidence")
  } else {
    ledger += record
  }
}
