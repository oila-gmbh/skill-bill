@file:Suppress("TooManyFunctions")

package skillbill.mcp.core

import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy

internal val emptyObjectSchema: Map<String, Any?> = McpToolSpec.strictObjectSchema()
internal val freeObjectSchema: Map<String, Any?> = mapOf("type" to "object")
internal val integerSchema: Map<String, Any?> = mapOf("type" to "integer")
internal val booleanSchema: Map<String, Any?> = mapOf("type" to "boolean")
internal val historySignalSchema: Map<String, Any?> =
  stringSchema(enum = listOf("none", "irrelevant", "low", "medium", "high"))
internal val qualityCheckScopeSchema: Map<String, Any?> =
  stringSchema(enum = listOf("files", "working_tree", "branch_diff", "repo"))

// FeatureTaskExecutionIdentityPolicy enforces the prefix, but callers see only this schema;
// without the description a bare absolute path is the natural — and rejected — guess.
internal val repositoryIdentitySchema: Map<String, Any?> = stringSchema(
  description = "Canonical repository identity: the literal prefix " +
    "'${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}' followed by the absolute " +
    "real path of the Git top-level directory. Example: " +
    "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/home/me/projects/app",
  pattern = "^${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/",
)
internal val remoteStatsWorkflowSchema: Map<String, Any?> =
  stringSchema(
    enum =
    listOf(
      "verify",
      "goal",
      "bill-feature-verify",
      "feature-task-runtime",
      "bill-feature-goal",
    ),
  )

internal fun objectSchema(
  required: List<String> = emptyList(),
  properties: Map<String, Map<String, Any?>> = emptyMap(),
): Map<String, Any?> = McpToolSpec.strictObjectSchema(required = required, properties = properties)

internal fun workflowIdSchema(): Map<String, Any?> = objectSchema(
  required = listOf("workflow_id"),
  properties = mapOf("workflow_id" to stringSchema()),
)

internal fun workflowOpenSchema(required: List<String> = emptyList()): Map<String, Any?> = objectSchema(
  required = required,
  properties = mapOf(
    "session_id" to stringSchema(),
    "current_step_id" to stringSchema(),
    "issue_key" to stringSchema(),
    "repository_identity" to repositoryIdentitySchema,
    "governed_spec_path" to stringSchema(),
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

internal fun remoteStatsSchema(): Map<String, Any?> = objectSchema(
  required = listOf("workflow"),
  properties = mapOf(
    "workflow" to remoteStatsWorkflowSchema,
    "since" to stringSchema(),
    "date_from" to stringSchema(),
    "date_to" to stringSchema(),
    "group_by" to stringSchema(enum = listOf("", "day", "week")),
  ),
)

internal fun goalStatsSchema(): Map<String, Any?> = objectSchema(
  properties = mapOf(
    "since" to stringSchema(),
    "date_from" to stringSchema(),
    "date_to" to stringSchema(),
    "group_by" to stringSchema(enum = listOf("", "day", "week")),
  ),
)

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

private fun stepUpdateSchema(stepIdEnum: List<String>): Map<String, Any?> = McpToolSpec.strictObjectSchema(
  required = listOf("step_id", "status", "attempt_count"),
  properties = mapOf(
    "step_id" to stringSchema(enum = stepIdEnum),
    "status" to stringSchema(enum = listOf("pending", "running", "completed", "failed", "blocked", "skipped")),
    "attempt_count" to integerSchema,
  ),
)
