package skillbill.ports.review

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord

/**
 * The single measured surface a delegated specialist may act through. Every call is policy-checked
 * and accounted; once a lane produces a terminal outcome the broker keeps returning that outcome
 * rather than serving more context.
 */
interface ReviewEvidenceBroker {
  fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
    error("This evidence broker does not support governed complete-file expansion.")

  fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult

  fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult

  fun recordModelTurn(): ReviewBudgetOutcome?

  fun validateLaneResult(result: String): ReviewBudgetOutcome?

  /** Observes cumulative provider result bytes while the lane is still running. */
  fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome?

  /** Distinguishes an observed empty provider result from a provider with no decoded result. */
  fun hasObservedLaneResult(): Boolean = accounting().resultBytes > 0

  fun accounting(): ReviewLaneAccounting

  fun terminalOutcome(): ReviewBudgetOutcome?
}

fun interface ReviewEvidenceBrokerFactory {
  fun brokerFor(binding: ReviewEvidenceBrokerBinding): ReviewEvidenceBroker
}

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
  fun payload(record: ReviewExpansionRecord): Map<String, Any?> =
    GovernedReviewEvidenceCodecWire.expansionPayload(record)
}

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

internal object GovernedReviewEvidenceCodecWireParsing {
  fun evidenceRequest(
    lane: String,
    raw: Any?,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceRequest {
    val map = asMap(raw)
    val expansionId = optionalString(map, "expansion_id")
    val authorized = expansionId?.let { id ->
      requireNotNull(expansionById(id)) { "Unknown expansion id '$id' for this lane." }
    }
    return ReviewEvidenceRequest(
      lane = lane,
      path = requiredString(map, "path"),
      reachabilityReason = optionalString(map, "reachability_reason") ?: authorized?.reachabilityReason,
      authorizedExpansion = authorized,
      offset = optionalLong(map, "offset"),
      limit = optionalLong(map, "limit"),
      paginationToken = optionalString(map, "pagination_token"),
    )
  }

  fun requiredString(source: Map<String, Any?>, key: String): String {
    val value = source[key]?.toString()
    require(!value.isNullOrBlank()) { "Governed evidence operation requires '$key'." }
    return value
  }

  private fun asMap(raw: Any?): Map<String, Any?> = requireNotNull(JsonCodec.anyToStringAnyMap(requireNotNull(raw))) {
    "Each governed evidence request must be an object."
  }

  private fun optionalString(source: Map<String, Any?>, key: String): String? =
    source[key]?.toString()?.takeIf(String::isNotBlank)

  private fun optionalLong(source: Map<String, Any?>, key: String): Long? = when (val value = source[key]) {
    null -> null
    is Number -> value.toLong()
    else -> value.toString().toLongOrNull()
  }
}

internal object GovernedReviewEvidenceCodecWirePayloads {
  fun expansionPayload(record: ReviewExpansionRecord): Map<String, Any?> = linkedMapOf(
    "expansion_id" to record.expansionId,
    "requested_path" to record.requestedPath,
    "reachability_reason" to record.reachabilityReason,
    "authorized" to record.authorized,
    "sequence" to record.sequence,
  )

  fun resultPayload(result: ReviewEvidenceResult): Map<String, Any?> {
    val refusal = refusal(result)
    if (refusal != null) return refusal
    return linkedMapOf(
      "refused" to false,
      "content" to result.content,
      "bytes" to result.bytes,
      "cumulative_bytes" to result.cumulativeBytes,
      "expansion_count" to result.expansionCount,
    )
  }

  fun budgetPayload(outcome: ReviewBudgetOutcome): Map<String, Any?> = linkedMapOf(
    "refused" to true,
    "refusal_kind" to "budget_exceeded",
    "reason" to outcome.type,
    "budget_kind" to outcome.budgetKind.wireValue,
    "configured_limit" to outcome.configuredLimit,
    "observed_value" to outcome.observedValue,
  )

  private fun refusal(result: ReviewEvidenceResult): Map<String, Any?>? {
    result.forbidden?.let { return forbiddenPayload(it) }
    result.budgetExceeded?.let { return budgetPayload(it) }
    return null
  }

  private fun forbiddenPayload(forbidden: ForbiddenReviewOperation): Map<String, Any?> = linkedMapOf(
    "refused" to true,
    "refusal_kind" to "forbidden",
    "reason" to forbidden.reason,
    "category" to forbidden.category,
    "target" to forbidden.target,
  )
}

internal object GovernedReviewEvidenceCodecWireSchemas {
  fun toolSpecs(): List<Map<String, Any?>> = listOf(
    toolSpec(
      GovernedReviewEvidenceCodec.READ_EVIDENCE,
      "Read admitted review evidence for the assigned lane. Bodies are pulled on demand by locator.",
      linkedMapOf(
        "requests" to linkedMapOf(
          "type" to "array",
          "items" to linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
              "path" to stringProperty("Repository-relative path inside the assignment surface."),
              "reachability_reason" to stringProperty("Why the path is reachable from the assignment."),
              "expansion_id" to stringProperty(
                "Identifier returned by ${GovernedReviewEvidenceCodec.REQUEST_EXPANSION}.",
              ),
              "offset" to linkedMapOf("type" to "integer"),
              "limit" to linkedMapOf("type" to "integer"),
              "pagination_token" to stringProperty("Continuation token from a previous read."),
            ),
            "required" to listOf("path"),
            "additionalProperties" to false,
          ),
        ),
      ),
      listOf("requests"),
    ),
    toolSpec(
      GovernedReviewEvidenceCodec.REQUEST_EXPANSION,
      "Request authorization to read a path beyond the assigned hunks.",
      linkedMapOf(
        "path" to stringProperty("Repository-relative path to expand to."),
        "reachability_reason" to stringProperty("Why the assignment reaches this path."),
      ),
      listOf("path", "reachability_reason"),
    ),
  )

  private fun toolSpec(
    name: String,
    description: String,
    properties: Map<String, Any?>,
    required: List<String>,
  ): Map<String, Any?> = linkedMapOf(
    "name" to name,
    "description" to description,
    "inputSchema" to linkedMapOf(
      "type" to "object",
      "properties" to properties,
      "required" to required,
      "additionalProperties" to false,
    ),
  )

  private fun stringProperty(description: String): Map<String, Any?> =
    linkedMapOf("type" to "string", "description" to description)
}
