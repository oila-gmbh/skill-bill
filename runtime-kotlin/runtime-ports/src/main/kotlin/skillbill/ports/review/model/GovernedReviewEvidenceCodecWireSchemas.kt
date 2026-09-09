package skillbill.ports.review.model

import skillbill.review.context.model.ReviewEvidenceLimits

internal object GovernedReviewEvidenceCodecWireSchemas {
  fun toolSpecs(): List<Map<String, Any?>> = listOf(
    toolSpec(
      GovernedReviewEvidenceCodec.READ_EVIDENCE,
      "Discover assignment selectors with operation=discover; read returned " +
        "selectors with operation=read and requests.",
      linkedMapOf(
        "operation" to linkedMapOf("type" to "string", "enum" to listOf("discover", "read")),
        "cursor" to (
          stringProperty(
            "Opaque next_cursor from the previous discovery page.",
          ) + mapOf("maxLength" to REVIEW_DISCOVERY_CURSOR_CHARACTERS)
          ),

        "page_size" to linkedMapOf("type" to "integer", "minimum" to 1, "maximum" to REVIEW_DISCOVERY_PAGE_SIZE),
        "requests" to linkedMapOf(
          "type" to "array",
          "minItems" to 1,
          "maxItems" to REVIEW_EVIDENCE_BATCH_SIZE,
          "items" to linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
              "selector" to stringProperty("Exact selector returned by discovery."),
              "path" to stringProperty("Repository-relative path inside the assignment surface."),
              "reachability_reason" to stringProperty("Why the path is reachable from the assignment."),
              "expansion_id" to stringProperty(
                "Identifier returned by ${GovernedReviewEvidenceCodec.REQUEST_EXPANSION}.",
              ),
            ),
            "required" to listOf("path"),
            "additionalProperties" to false,
          ),
        ),
      ),
      emptyList(),
    ),
    toolSpec(
      GovernedReviewEvidenceCodec.REQUEST_EXPANSION,
      "Request authorization to read a path beyond the assigned hunks.",
      linkedMapOf(
        "lane" to stringProperty("Original source lane from discovery ownership; defaults to the bound lane."),
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
    ).apply {
      if (name == GovernedReviewEvidenceCodec.READ_EVIDENCE) {
        put(
          "oneOf",
          listOf(
            mapOf(
              "required" to listOf("operation"),
              "properties" to mapOf("operation" to mapOf("const" to "discover")),
              "not" to mapOf("required" to listOf("requests")),
            ),
            mapOf(
              "required" to listOf("requests"),
              "properties" to mapOf("operation" to mapOf("const" to "read")),
              "not" to mapOf(
                "anyOf" to listOf(
                  mapOf("required" to listOf("cursor")),
                  mapOf("required" to listOf("page_size")),
                ),
              ),
            ),

          ),
        )
      }
    },
  )

  private fun stringProperty(description: String): Map<String, Any?> = linkedMapOf(
    "type" to "string",
    "minLength" to 1,
    "maxLength" to ReviewEvidenceLimits.FIELD_CHARACTERS,
    "description" to description,
  )
}
