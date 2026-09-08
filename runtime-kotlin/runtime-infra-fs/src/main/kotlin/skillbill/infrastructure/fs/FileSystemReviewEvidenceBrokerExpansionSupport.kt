package skillbill.infrastructure.fs

import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val EXPANSION_ID_HEX_LENGTH = 24

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
    result.forbidden?.let { refusalLedger += ReviewRefusedOperationRecord(it.category, it.target) }
    result.budgetExceeded?.let { refusalLedger += ReviewRefusedOperationRecord(it.type, it.budgetKind) }
  }
  return ReviewEvidenceBatchResult(results, cumulativeBytes, expansionLedger, outcome)
}
