package skillbill.ports.review.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewEvidenceLimits
import skillbill.review.context.model.ReviewExpansionRecord

object GovernedReviewEvidenceCodec {
  const val REQUEST_BYTES: Int = ReviewEvidenceLimits.REQUEST_BYTES
  const val RESPONSE_FRAME_BYTES: Int = ReviewEvidenceLimits.RESPONSE_FRAME_BYTES

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
  fun discoveryRequest(arguments: Map<String, Any?>): ReviewEvidenceDiscoveryRequest {
    requestMetadata(arguments)
    if (arguments.keys.any {
        it !in setOf(
          "operation",
          "cursor",
          "page_size",
        )
      } || arguments["operation"] != "discover"
    ) {
      throw InvalidReviewContextSchemaError("review-discovery", "Malformed discovery request.")
    }
    val cursor = discoveryCursor(arguments)
    val size = discoveryPageSize(arguments)
    return ReviewEvidenceDiscoveryRequest(cursor, size)
  }

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun payload(page: ReviewEvidenceDiscoveryPage): Map<String, Any?> = linkedMapOf(
    "assignment_digest" to page.assignmentDigest,
    "next_cursor" to page.nextCursor,
    "entries" to page.entries.map { entry ->
      linkedMapOf(
        "selector" to entry.selector,
        "path" to entry.path,
        "owners" to entry.owners.map { owner ->
          linkedMapOf(
            "lane" to owner.lane,
            "assignment_digest" to owner.assignmentDigest,
            "rubric_id" to owner.rubricId,
            "unit_id" to owner.unitId,
          )
        },
      ).apply { entry.expansionId?.let { put("expansion_id", it) } }
    },
  )

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun readRequest(
    lane: String,
    arguments: Map<String, Any?>,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceBatchRequest {
    requestMetadata(arguments)
    if (arguments.keys.any { it !in setOf("operation", "requests") } ||
      ("operation" in arguments && arguments["operation"] != "read")
    ) {
      throw InvalidReviewContextSchemaError("review-evidence", "Malformed read operation.")
    }
    val rawRequests = evidenceReadItems(arguments)
    return ReviewEvidenceBatchRequest(
      lane = lane,
      requests = rawRequests.map { raw -> GovernedReviewEvidenceCodecWire.evidenceRequest(lane, raw, expansionById) },
    )
  }

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun expansionRequest(lane: String, arguments: Map<String, Any?>): ReviewExpansionAuthorizationRequest {
    requestMetadata(arguments)
    if (arguments.keys.any { it !in setOf("lane", "path", "reachability_reason") }) {
      throw InvalidReviewContextSchemaError("review-expansion", "Unknown expansion request field.")
    }
    return ReviewExpansionAuthorizationRequest(
      lane = if ("lane" in arguments) GovernedReviewEvidenceCodecWire.requiredString(arguments, "lane") else lane,
      path = GovernedReviewEvidenceCodecWire.requiredString(arguments, "path"),
      reachabilityReason = GovernedReviewEvidenceCodecWire.requiredString(arguments, "reachability_reason"),
    )
  }

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun payload(result: ReviewEvidenceBatchResult): Map<String, Any?> = linkedMapOf(
    "delivery_receipt" to result.deliveryReceipt,
    "results" to result.results.map(GovernedReviewEvidenceCodecWire::resultPayload),
    "cumulative_bytes" to result.cumulativeBytes,
    "expansions" to result.expansions.map(GovernedReviewEvidenceCodecWire::expansionPayload),
    "terminal_outcome" to result.terminalOutcome?.let(GovernedReviewEvidenceCodecWire::budgetPayload),
  )

  @OpenBoundaryMap("JSON-RPC wire maps at the governed review evidence MCP seam")
  fun payload(record: ReviewExpansionRecord): Map<String, Any?> =
    GovernedReviewEvidenceCodecWire.expansionPayload(record)
  fun requestMetadataBytes(request: ReviewEvidenceBatchRequest): Int = JsonSupport.mapToJsonString(
    mapOf(
      "lane" to request.lane,
      "requests" to request.requests.map { item ->
        mapOf(
          "lane" to item.lane,
          "path" to item.path,
          "selector" to item.selector,
          "reachability_reason" to item.reachabilityReason,
          "authorized_expansion" to item.authorizedExpansion?.let(::payload),
          "offset" to item.offset,
          "limit" to item.limit,
          "pagination_token" to item.paginationToken,
        )
      },
    ),
  ).toByteArray(Charsets.UTF_8).size

  private fun requestMetadata(arguments: Map<String, Any?>) {
    if (JsonSupport.mapToJsonString(arguments).toByteArray(Charsets.UTF_8).size > ReviewEvidenceLimits.REQUEST_BYTES) {
      throw InvalidReviewContextSchemaError("review-evidence", "Request metadata exceeds its byte limit.")
    }
  }
}
