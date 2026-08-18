package skillbill.ports.review.model

import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewExpansionRecord

/**
 * Wire mapping between the two governed MCP operations and the evidence models the broker already
 * speaks. It decides nothing: reachability, byte budgeting, the expansion ledger, and lane
 * termination all stay behind [skillbill.ports.review.NativeReviewOperationProtocol].
 */
object GovernedReviewEvidenceCodec {
  const val READ_EVIDENCE: String = "read_evidence"
  const val REQUEST_EXPANSION: String = "request_expansion"
  const val SERVER_NAME: String = "skill-bill-review-evidence"
  const val SOCKET_ENV: String = "SKILL_BILL_REVIEW_EVIDENCE_SOCKET"
  const val TOKEN_ENV: String = "SKILL_BILL_REVIEW_EVIDENCE_TOKEN"
  const val LANE_ENV: String = "SKILL_BILL_REVIEW_EVIDENCE_LANE"

  val OPERATIONS: List<String> = listOf(READ_EVIDENCE, REQUEST_EXPANSION)

  val TOOL_SPECS: List<Map<String, Any?>> = listOf(
    toolSpec(
      READ_EVIDENCE,
      "Read admitted review evidence for the assigned lane. Bodies are pulled on demand by locator.",
      linkedMapOf(
        "requests" to linkedMapOf(
          "type" to "array",
          "items" to linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
              "path" to stringProperty("Repository-relative path inside the assignment surface."),
              "reachability_reason" to stringProperty("Why the path is reachable from the assignment."),
              "expansion_id" to stringProperty("Identifier returned by $REQUEST_EXPANSION."),
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
      REQUEST_EXPANSION,
      "Request authorization to read a path beyond the assigned hunks.",
      linkedMapOf(
        "path" to stringProperty("Repository-relative path to expand to."),
        "reachability_reason" to stringProperty("Why the assignment reaches this path."),
      ),
      listOf("path", "reachability_reason"),
    ),
  )

  fun readRequest(
    lane: String,
    arguments: Map<String, Any?>,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceBatchRequest {
    val rawRequests = (arguments["requests"] as? List<*>).orEmpty()
    require(rawRequests.isNotEmpty()) { "$READ_EVIDENCE requires at least one request." }
    return ReviewEvidenceBatchRequest(
      lane = lane,
      requests = rawRequests.map { raw -> evidenceRequest(lane, asMap(raw), expansionById) },
    )
  }

  fun expansionRequest(lane: String, arguments: Map<String, Any?>): ReviewExpansionAuthorizationRequest =
    ReviewExpansionAuthorizationRequest(
      lane = lane,
      path = requiredString(arguments, "path"),
      reachabilityReason = requiredString(arguments, "reachability_reason"),
    )

  fun payload(result: ReviewEvidenceBatchResult): Map<String, Any?> = linkedMapOf(
    "results" to result.results.map(::payload),
    "cumulative_bytes" to result.cumulativeBytes,
    "expansions" to result.expansions.map(::payload),
    "terminal_outcome" to result.terminalOutcome?.let(::payload),
  )

  fun payload(record: ReviewExpansionRecord): Map<String, Any?> = linkedMapOf(
    "expansion_id" to record.expansionId,
    "requested_path" to record.requestedPath,
    "reachability_reason" to record.reachabilityReason,
    "authorized" to record.authorized,
    "sequence" to record.sequence,
  )

  private fun payload(result: ReviewEvidenceResult): Map<String, Any?> {
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

  private fun budgetPayload(outcome: ReviewBudgetOutcome): Map<String, Any?> = linkedMapOf(
    "refused" to true,
    "refusal_kind" to "budget_exceeded",
    "reason" to outcome.type,
    "budget_kind" to outcome.budgetKind,
    "configured_limit" to outcome.configuredLimit,
    "observed_value" to outcome.observedValue,
  )

  private fun evidenceRequest(
    lane: String,
    raw: Map<String, Any?>,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceRequest {
    val expansionId = optionalString(raw, "expansion_id")
    val authorized = expansionId?.let { id ->
      requireNotNull(expansionById(id)) { "Unknown expansion id '$id' for this lane." }
    }
    return ReviewEvidenceRequest(
      lane = lane,
      path = requiredString(raw, "path"),
      reachabilityReason = optionalString(raw, "reachability_reason"),
      authorizedExpansion = authorized,
      offset = optionalLong(raw, "offset"),
      limit = optionalLong(raw, "limit"),
      paginationToken = optionalString(raw, "pagination_token"),
    )
  }

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

  @Suppress("UNCHECKED_CAST")
  private fun asMap(raw: Any?): Map<String, Any?> =
    requireNotNull(raw as? Map<String, Any?>) { "Each governed evidence request must be an object." }

  private fun requiredString(source: Map<String, Any?>, key: String): String {
    val value = source[key]?.toString()
    require(!value.isNullOrBlank()) { "Governed evidence operation requires '$key'." }
    return value
  }

  private fun optionalString(source: Map<String, Any?>, key: String): String? =
    source[key]?.toString()?.takeIf(String::isNotBlank)

  private fun optionalLong(source: Map<String, Any?>, key: String): Long? = when (val value = source[key]) {
    null -> null
    is Number -> value.toLong()
    else -> value.toString().toLongOrNull()
  }
}
