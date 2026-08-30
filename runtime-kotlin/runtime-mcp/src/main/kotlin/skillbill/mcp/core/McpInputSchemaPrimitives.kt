package skillbill.mcp.core

internal fun stringSchema(
  enum: List<String> = emptyList(),
  minLength: Int? = null,
  description: String? = null,
  pattern: String? = null,
): Map<String, Any?> = buildMap {
  put("type", "string")
  if (enum.isNotEmpty()) {
    put("enum", enum)
  }
  minLength?.let { put("minLength", it) }
  description?.let { put("description", it) }
  pattern?.let { put("pattern", it) }
}

internal fun arraySchema(items: Map<String, Any?>): Map<String, Any?> = mapOf(
  "type" to "array",
  "items" to items,
)

internal fun stepUpdateSchema(stepIdEnum: List<String>): Map<String, Any?> = McpToolSpec.strictObjectSchema(
  required = listOf("step_id", "status", "attempt_count"),
  properties = mapOf(
    "step_id" to stringSchema(enum = stepIdEnum),
    "status" to stringSchema(enum = listOf("pending", "running", "completed", "failed", "blocked", "skipped")),
    "attempt_count" to integerSchema,
  ),
)
