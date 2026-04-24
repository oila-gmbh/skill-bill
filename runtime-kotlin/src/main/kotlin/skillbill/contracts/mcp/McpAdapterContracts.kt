package skillbill.contracts.mcp

import skillbill.contracts.JsonPayloadContract

data class McpReviewImportSkippedContract(
  val reason: String,
  val reviewRunId: String?,
  val findingCount: Any?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "status" to "skipped",
    "reason" to reason,
    "review_run_id" to reviewRunId,
    "finding_count" to findingCount,
  )
}

data class McpTriageSkippedContract(
  val reason: String,
  val reviewRunId: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "status" to "skipped",
    "reason" to reason,
    "review_run_id" to reviewRunId,
  )
}

data class McpLearningsSkippedContract(
  val reason: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "status" to "skipped",
    "reason" to reason,
    "applied_learnings" to "none",
    "learnings" to emptyList<Any>(),
  )
}

data class McpOrchestratedPayloadContract(
  val basePayload: Map<String, Any?>,
  val telemetryPayload: Map<String, Any?>?,
  val telemetrySkill: String = "bill-code-review",
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    putAll(basePayload)
    put("mode", "orchestrated")
    telemetryPayload?.let { telemetry ->
      put(
        "telemetry_payload",
        linkedMapOf<String, Any?>().apply {
          putAll(telemetry)
          put("skill", telemetrySkill)
        },
      )
    }
  }
}
