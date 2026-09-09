package skillbill.infrastructure.fs

import skillbill.contracts.JsonSupport
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.review.context.model.ReviewEvidenceLimits
import skillbill.review.context.model.ReviewExpansionRecord

private const val DELIVERY_RECEIPT_RESERVE_BYTES = 512

internal fun expansionLedgerBytes(records: List<ReviewExpansionRecord>): Int =
  JsonSupport.mapToJsonString(mapOf("expansions" to records.map { GovernedReviewEvidenceCodec.payload(it) }))
    .toByteArray(Charsets.UTF_8).size

internal fun boundedReviewEvidenceBatch(
  state: FileSystemReviewEvidenceBrokerReadState,
  batch: ReviewEvidenceBatchResult,
): ReviewEvidenceBatchResult {
  fun size(value: ReviewEvidenceBatchResult): Int =
    JsonSupport.mapToJsonString(GovernedReviewEvidenceCodec.payload(value)).toByteArray(Charsets.UTF_8).size
  val metadataBytes = size(batch.copy(results = batch.results.map { it.copy(content = null) }))
  if (metadataBytes > ReviewEvidenceLimits.METADATA_BYTES - DELIVERY_RECEIPT_RESERVE_BYTES) {
    val refusal = exceededEvidence(
      state,
      "evidence_metadata_bytes",
      (ReviewEvidenceLimits.METADATA_BYTES - DELIVERY_RECEIPT_RESERVE_BYTES).toLong(),
      metadataBytes.toLong(),
    )
    return batch.copy(results = listOf(refusal), expansions = emptyList(), terminalOutcome = state.terminalOutcome)
  }
  var bounded = batch
  while (size(bounded) > ReviewEvidenceLimits.RESPONSE_PAYLOAD_BYTES - DELIVERY_RECEIPT_RESERVE_BYTES) {
    val observed = size(bounded)
    val last = bounded.results.indexOfLast { it.content != null }
    val refusal = exceededEvidence(
      state,
      "evidence_response_bytes",
      (ReviewEvidenceLimits.RESPONSE_PAYLOAD_BYTES - DELIVERY_RECEIPT_RESERVE_BYTES).toLong(),
      observed.toLong(),
    )
    if (last < 0) {
      return bounded.copy(
        results = listOf(refusal),
        expansions = emptyList(),
        terminalOutcome = state.terminalOutcome,
      )
    }
    bounded = bounded.copy(
      results = bounded.results.mapIndexed { index, result -> if (index == last) refusal else result },
      terminalOutcome = state.terminalOutcome,
    )
  }
  return bounded
}
