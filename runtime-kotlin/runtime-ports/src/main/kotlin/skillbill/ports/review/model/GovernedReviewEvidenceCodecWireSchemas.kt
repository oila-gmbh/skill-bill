package skillbill.ports.review.model

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
