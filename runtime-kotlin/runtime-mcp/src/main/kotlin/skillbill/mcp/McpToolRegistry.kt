package skillbill.mcp

data class McpToolSpec(
  val name: String,
  val description: String,
  val inputSchema: Map<String, Any?> = openObjectSchema(),
) {
  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "name" to name,
    "description" to description,
    "inputSchema" to inputSchema,
  )

  companion object {
    fun openObjectSchema(): Map<String, Any?> = mapOf(
      "type" to "object",
      "additionalProperties" to true,
    )
  }
}

object McpToolRegistry {
  private val toolNames: List<String> =
    listOf(
      "doctor",
      "feature_implement_finished",
      "feature_implement_stats",
      "feature_implement_started",
      "feature_implement_workflow_get",
      "feature_implement_workflow_latest",
      "feature_implement_workflow_list",
      "feature_implement_workflow_continue",
      "feature_implement_workflow_open",
      "feature_implement_workflow_resume",
      "feature_implement_workflow_update",
      "feature_verify_finished",
      "feature_verify_stats",
      "feature_verify_started",
      "feature_verify_workflow_get",
      "feature_verify_workflow_latest",
      "feature_verify_workflow_list",
      "feature_verify_workflow_continue",
      "feature_verify_workflow_open",
      "feature_verify_workflow_resume",
      "feature_verify_workflow_update",
      "import_review",
      "new_skill_scaffold",
      "pr_description_generated",
      "quality_check_finished",
      "quality_check_started",
      "resolve_learnings",
      "review_stats",
      "telemetry_proxy_capabilities",
      "telemetry_remote_stats",
      "triage_findings",
    )

  private val descriptions: Map<String, String> =
    mapOf(
      "doctor" to "Check skill-bill installation health.",
      "feature_implement_finished" to "Record completion of a feature-implement session.",
      "feature_implement_stats" to "Show aggregate bill-feature-implement metrics.",
      "feature_implement_started" to "Record start of a feature-implement session.",
      "feature_implement_workflow_continue" to "Continue durable bill-feature-implement workflow state.",
      "feature_implement_workflow_get" to "Fetch durable bill-feature-implement workflow state.",
      "feature_implement_workflow_latest" to "Fetch the latest bill-feature-implement workflow.",
      "feature_implement_workflow_list" to "List bill-feature-implement workflows.",
      "feature_implement_workflow_open" to "Open durable bill-feature-implement workflow state.",
      "feature_implement_workflow_resume" to "Summarize bill-feature-implement workflow resume state.",
      "feature_implement_workflow_update" to "Update durable bill-feature-implement workflow state.",
      "feature_verify_finished" to "Record completion of a feature-verify session.",
      "feature_verify_stats" to "Show aggregate bill-feature-verify metrics.",
      "feature_verify_started" to "Record start of a feature-verify session.",
      "feature_verify_workflow_continue" to "Continue durable bill-feature-verify workflow state.",
      "feature_verify_workflow_get" to "Fetch durable bill-feature-verify workflow state.",
      "feature_verify_workflow_latest" to "Fetch the latest bill-feature-verify workflow.",
      "feature_verify_workflow_list" to "List bill-feature-verify workflows.",
      "feature_verify_workflow_open" to "Open durable bill-feature-verify workflow state.",
      "feature_verify_workflow_resume" to "Summarize bill-feature-verify workflow resume state.",
      "feature_verify_workflow_update" to "Update durable bill-feature-verify workflow state.",
      "import_review" to "Import code review output into the local telemetry store.",
      "new_skill_scaffold" to "Scaffold a new skill from a validated payload.",
      "pr_description_generated" to "Record PR description generation telemetry.",
      "quality_check_finished" to "Record completion of a quality-check session.",
      "quality_check_started" to "Record start of a quality-check session.",
      "resolve_learnings" to "Resolve active learnings for a review context.",
      "review_stats" to "Show review acceptance metrics.",
      "telemetry_proxy_capabilities" to "Show configured telemetry proxy capabilities.",
      "telemetry_remote_stats" to "Fetch aggregate org-wide workflow metrics.",
      "triage_findings" to "Record triage decisions for imported review findings.",
    )

  private val inputSchemas: Map<String, Map<String, Any?>> =
    mapOf(
      "feature_implement_started" to objectSchema(
        required = listOf(
          "feature_size",
          "acceptance_criteria_count",
          "open_questions_count",
          "spec_input_types",
          "spec_word_count",
          "rollout_needed",
          "feature_name",
          "issue_key",
          "spec_summary",
        ),
        properties = mapOf(
          "feature_size" to stringSchema(enum = listOf("SMALL", "MEDIUM", "LARGE")),
          "acceptance_criteria_count" to integerSchema(),
          "open_questions_count" to integerSchema(),
          "spec_input_types" to arraySchema(stringSchema()),
          "spec_word_count" to integerSchema(),
          "rollout_needed" to booleanSchema(),
          "feature_name" to stringSchema(),
          "issue_key" to stringSchema(),
          "issue_key_type" to stringSchema(enum = listOf("jira", "linear", "github", "other", "none")),
          "spec_summary" to stringSchema(),
        ),
      ),
      "feature_implement_finished" to objectSchema(
        required = listOf(
          "session_id",
          "completion_status",
          "plan_correction_count",
          "plan_task_count",
          "plan_phase_count",
          "feature_flag_used",
          "files_created",
          "files_modified",
          "tasks_completed",
          "review_iterations",
          "audit_result",
          "audit_iterations",
          "validation_result",
          "boundary_history_written",
          "pr_created",
          "plan_deviation_notes",
        ),
        properties = mapOf(
          "session_id" to stringSchema(),
          "completion_status" to stringSchema(
            enum = listOf(
              "completed",
              "abandoned_at_planning",
              "abandoned_at_implementation",
              "abandoned_at_review",
              "error",
            ),
          ),
          "plan_correction_count" to integerSchema(),
          "plan_task_count" to integerSchema(),
          "plan_phase_count" to integerSchema(),
          "feature_flag_used" to booleanSchema(),
          "files_created" to integerSchema(),
          "files_modified" to integerSchema(),
          "tasks_completed" to integerSchema(),
          "review_iterations" to integerSchema(),
          "audit_result" to stringSchema(enum = listOf("all_pass", "had_gaps", "skipped")),
          "audit_iterations" to integerSchema(),
          "validation_result" to stringSchema(enum = listOf("pass", "fail", "skipped")),
          "boundary_history_written" to booleanSchema(),
          "pr_created" to booleanSchema(),
          "feature_flag_pattern" to stringSchema(enum = listOf("simple_conditional", "di_switch", "legacy", "none")),
          "boundary_history_value" to stringSchema(enum = listOf("none", "irrelevant", "low", "medium", "high")),
          "plan_deviation_notes" to stringSchema(),
          "child_steps" to arraySchema(mapOf("type" to "object", "additionalProperties" to true)),
        ),
      ),
    )

  val tools: List<McpToolSpec> =
    toolNames.map { name ->
      McpToolSpec(name, descriptions.getValue(name), inputSchemas[name] ?: McpToolSpec.openObjectSchema())
    }

  private fun objectSchema(required: List<String>, properties: Map<String, Map<String, Any?>>): Map<String, Any?> =
    mapOf(
      "type" to "object",
      "additionalProperties" to false,
      "properties" to properties,
      "required" to required,
    )

  private fun stringSchema(enum: List<String> = emptyList()): Map<String, Any?> = if (enum.isEmpty()) {
    mapOf("type" to "string")
  } else {
    mapOf("type" to "string", "enum" to enum)
  }

  private fun integerSchema(): Map<String, Any?> = mapOf("type" to "integer")

  private fun booleanSchema(): Map<String, Any?> = mapOf("type" to "boolean")

  private fun arraySchema(items: Map<String, Any?>): Map<String, Any?> = mapOf(
    "type" to "array",
    "items" to items,
  )
}
