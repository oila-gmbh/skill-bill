package skillbill.mcp

internal val emptyObjectSchema: Map<String, Any?> = McpToolSpec.strictObjectSchema()
internal val freeObjectSchema: Map<String, Any?> = mapOf("type" to "object")
internal val integerSchema: Map<String, Any?> = mapOf("type" to "integer")
internal val booleanSchema: Map<String, Any?> = mapOf("type" to "boolean")
internal val historySignalSchema: Map<String, Any?> =
  stringSchema(enum = listOf("none", "irrelevant", "low", "medium", "high"))
internal val qualityCheckScopeSchema: Map<String, Any?> =
  stringSchema(enum = listOf("files", "working_tree", "branch_diff", "repo"))

internal fun objectSchema(
  required: List<String> = emptyList(),
  properties: Map<String, Map<String, Any?>> = emptyMap(),
): Map<String, Any?> = McpToolSpec.strictObjectSchema(required = required, properties = properties)

internal fun passthroughObjectSchema(
  required: List<String> = emptyList(),
  properties: Map<String, Map<String, Any?>> = emptyMap(),
): Map<String, Any?> = linkedMapOf<String, Any?>(
  "type" to "object",
  "additionalProperties" to true,
  "properties" to properties,
  "required" to required,
)

internal fun workflowIdSchema(): Map<String, Any?> = objectSchema(
  required = listOf("workflow_id"),
  properties = mapOf("workflow_id" to stringSchema()),
)

internal fun workflowOpenSchema(): Map<String, Any?> = objectSchema(
  properties = mapOf(
    "session_id" to stringSchema(),
    "current_step_id" to stringSchema(),
  ),
)

internal fun workflowListSchema(): Map<String, Any?> = objectSchema(
  properties = mapOf("limit" to integerSchema),
)

internal fun workflowUpdateSchema(workflowStatusEnum: List<String>, stepIdEnum: List<String>): Map<String, Any?> =
  objectSchema(
    required = listOf("workflow_id", "workflow_status", "current_step_id"),
    properties = mapOf(
      "workflow_id" to stringSchema(),
      "workflow_status" to stringSchema(enum = workflowStatusEnum),
      "current_step_id" to stringSchema(enum = stepIdEnum),
      "step_updates" to arraySchema(stepUpdateSchema(stepIdEnum)),
      "artifacts_patch" to freeObjectSchema,
      "session_id" to stringSchema(),
    ),
  )

internal fun stringSchema(enum: List<String> = emptyList()): Map<String, Any?> = if (enum.isEmpty()) {
  mapOf("type" to "string")
} else {
  mapOf("type" to "string", "enum" to enum)
}

internal fun arraySchema(items: Map<String, Any?>): Map<String, Any?> = mapOf(
  "type" to "array",
  "items" to items,
)

private fun stepUpdateSchema(stepIdEnum: List<String>): Map<String, Any?> = McpToolSpec.strictObjectSchema(
  required = listOf("step_id", "status", "attempt_count"),
  properties = mapOf(
    "step_id" to stringSchema(enum = stepIdEnum),
    "status" to stringSchema(enum = listOf("pending", "running", "completed", "failed", "blocked", "skipped")),
    "attempt_count" to integerSchema,
  ),
)
