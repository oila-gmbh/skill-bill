package skillbill.ports.review.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord

object GovernedReviewEvidenceCodec {
  const val READ_EVIDENCE: String = "read_evidence"
  const val REQUEST_EXPANSION: String = "request_expansion"
  const val SERVER_NAME: String = "skill-bill-review-evidence"
  const val SOCKET_ENV: String = "SKILL_BILL_REVIEW_EVIDENCE_SOCKET"
  const val TOKEN_ENV: String = "SKILL_BILL_REVIEW_EVIDENCE_TOKEN"
  const val LANE_ENV: String = "SKILL_BILL_REVIEW_EVIDENCE_LANE"

  val OPERATIONS: List<String> = listOf(READ_EVIDENCE, REQUEST_EXPANSION)

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  val TOOL_SPECS: List<Map<String, Any?>> = GovernedReviewEvidenceCodecWire.toolSpecs()

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun readRequest(
    lane: String,
    arguments: Map<String, Any?>,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceBatchRequest {
    val rawRequests = (arguments["requests"] as? List<*>).orEmpty()
    require(rawRequests.isNotEmpty()) { "$READ_EVIDENCE requires at least one request." }
    return ReviewEvidenceBatchRequest(
      lane = lane,
      requests = rawRequests.map { raw -> GovernedReviewEvidenceCodecWire.evidenceRequest(lane, raw, expansionById) },
    )
  }

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun expansionRequest(lane: String, arguments: Map<String, Any?>): ReviewExpansionAuthorizationRequest =
    ReviewExpansionAuthorizationRequest(
      lane = lane,
      path = GovernedReviewEvidenceCodecWire.requiredString(arguments, "path"),
      reachabilityReason = GovernedReviewEvidenceCodecWire.requiredString(arguments, "reachability_reason"),
    )

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun payload(result: ReviewEvidenceBatchResult): Map<String, Any?> = linkedMapOf(
    "results" to result.results.map(GovernedReviewEvidenceCodecWire::resultPayload),
    "cumulative_bytes" to result.cumulativeBytes,
    "expansions" to result.expansions.map(GovernedReviewEvidenceCodecWire::expansionPayload),
    "terminal_outcome" to result.terminalOutcome?.let(GovernedReviewEvidenceCodecWire::budgetPayload),
  )

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun payload(record: ReviewExpansionRecord): Map<String, Any?> = GovernedReviewEvidenceCodecWire.expansionPayload(record)
}
