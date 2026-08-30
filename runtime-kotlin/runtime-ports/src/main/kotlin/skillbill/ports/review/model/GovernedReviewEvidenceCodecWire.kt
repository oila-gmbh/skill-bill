package skillbill.ports.review.model

import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord

internal object GovernedReviewEvidenceCodecWire {
  fun toolSpecs(): List<Map<String, Any?>> = GovernedReviewEvidenceCodecWireSchemas.toolSpecs()

  fun expansionPayload(record: ReviewExpansionRecord): Map<String, Any?> =
    GovernedReviewEvidenceCodecWirePayloads.expansionPayload(record)

  fun resultPayload(result: ReviewEvidenceResult): Map<String, Any?> =
    GovernedReviewEvidenceCodecWirePayloads.resultPayload(result)

  fun budgetPayload(outcome: ReviewBudgetOutcome): Map<String, Any?> =
    GovernedReviewEvidenceCodecWirePayloads.budgetPayload(outcome)

  fun evidenceRequest(
    lane: String,
    raw: Any?,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceRequest = GovernedReviewEvidenceCodecWireParsing.evidenceRequest(lane, raw, expansionById)

  fun requiredString(source: Map<String, Any?>, key: String): String =
    GovernedReviewEvidenceCodecWireParsing.requiredString(source, key)
}
