@file:Suppress("TooManyFunctions")

package skillbill.mcp

internal val emptyObjectSchema: Map<String, Any?> = McpToolSpec.strictObjectSchema()
internal val freeObjectSchema: Map<String, Any?> = mapOf("type" to "object")
internal val integerSchema: Map<String, Any?> = mapOf("type" to "integer")
internal val booleanSchema: Map<String, Any?> = mapOf("type" to "boolean")
internal val historySignalSchema: Map<String, Any?> =
  stringSchema(enum = listOf("none", "irrelevant", "low", "medium", "high"))
internal val qualityCheckScopeSchema: Map<String, Any?> =
  stringSchema(enum = listOf("files", "working_tree", "branch_diff", "repo"))
internal val remoteStatsWorkflowSchema: Map<String, Any?> =
  stringSchema(
    enum =
    listOf(
      "verify",
      "implement",
      "bill-feature-verify",
      "bill-feature-task",
      "feature-task-runtime",
      "goal",
      "bill-feature-goal",
    ),
  )

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

internal fun stringSchema(enum: List<String> = emptyList()): Map<String, Any?> = if (enum.isEmpty()) {
  mapOf("type" to "string")
} else {
  mapOf("type" to "string", "enum" to enum)
}

internal fun arraySchema(items: Map<String, Any?>): Map<String, Any?> = mapOf(
  "type" to "array",
  "items" to items,
)

internal fun featureImplementChildStepSchema(): Map<String, Any?> = mapOf(
  "oneOf" to listOf(
    reviewChildStepSchema(),
    qualityCheckChildStepSchema(),
    prDescriptionChildStepSchema(),
  ),
)

private fun reviewChildStepSchema(): Map<String, Any?> = passthroughObjectSchema(
  required = listOf(
    "skill",
    "total_findings",
    "accepted_findings",
    "rejected_findings",
    "unresolved_findings",
    "accepted_rate",
    "rejected_rate",
  ),
  properties = mapOf(
    "skill" to stringSchema(),
    "total_findings" to integerSchema,
    "accepted_findings" to integerSchema,
    "rejected_findings" to integerSchema,
    "unresolved_findings" to integerSchema,
    "accepted_rate" to mapOf("type" to "number"),
    "rejected_rate" to mapOf("type" to "number"),
    "accepted_finding_details" to arraySchema(reviewFindingDetailSchema()),
    "rejected_finding_details" to arraySchema(reviewFindingDetailSchema()),
  ),
)

private fun reviewFindingDetailSchema(): Map<String, Any?> = passthroughObjectSchema(
  required = listOf("issue_category", "severity", "confidence", "outcome_type"),
  properties = mapOf(
    "issue_category" to stringSchema(),
    "severity" to stringSchema(enum = listOf("Blocker", "Major", "Minor")),
    "confidence" to stringSchema(enum = listOf("High", "Medium", "Low", "high", "medium", "low")),
    "outcome_type" to stringSchema(
      enum = listOf("finding_accepted", "fix_applied", "finding_edited", "fix_rejected", "false_positive"),
    ),
  ),
)

private fun qualityCheckChildStepSchema(): Map<String, Any?> = passthroughObjectSchema(
  required = listOf("skill", "result", "iterations", "initial_failure_count", "final_failure_count"),
  properties = mapOf(
    "skill" to stringSchema(),
    "result" to stringSchema(enum = listOf("pass", "fail", "skipped", "unsupported_stack")),
    "iterations" to integerSchema,
    "initial_failure_count" to integerSchema,
    "final_failure_count" to integerSchema,
    "failing_check_names" to arraySchema(stringSchema()),
    "failing_check_details" to arraySchema(freeObjectSchema),
  ),
)

private fun prDescriptionChildStepSchema(): Map<String, Any?> = passthroughObjectSchema(
  required = listOf("skill", "pr_created", "commit_count", "files_changed_count"),
  properties = mapOf(
    "skill" to stringSchema(),
    "pr_created" to booleanSchema,
    "commit_count" to integerSchema,
    "files_changed_count" to integerSchema,
  ),
)

private fun stepUpdateSchema(stepIdEnum: List<String>): Map<String, Any?> = McpToolSpec.strictObjectSchema(
  required = listOf("step_id", "status", "attempt_count"),
  properties = mapOf(
    "step_id" to stringSchema(enum = stepIdEnum),
    "status" to stringSchema(enum = listOf("pending", "running", "completed", "failed", "blocked", "skipped")),
    "attempt_count" to integerSchema,
  ),
)
