package skillbill.mcp.core

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

    fun strictObjectSchema(
      required: List<String> = emptyList(),
      properties: Map<String, Map<String, Any?>> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf(
      "type" to "object",
      "additionalProperties" to false,
      "properties" to properties,
      "required" to required,
    )
  }
}

object McpToolRegistry {
  private val toolNames: List<String> =
    listOf(
      "doctor",
      "feature_task_audit_settle",
      "feature_task_phase_block",
      "feature_task_phase_complete",
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
      "goal_stats",
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
      "update_check",
    )

  private val descriptions: Map<String, String> =
    mapOf(
      "doctor" to "Check skill-bill installation health.",
      "feature_task_audit_settle" to
        "Settle a feature-task audit phase via durable settlement (preferred over stdout envelope).",
      "feature_task_phase_block" to
        "Durable-block a prose feature-task phase (preplan|plan|implement|audit).",
      "feature_task_phase_complete" to
        "Complete a prose feature-task phase (preplan|plan|implement) via durable settlement.",
      "feature_verify_finished" to "Record completion of a feature-verify session.",
      "feature_verify_stats" to "Show aggregate bill-feature-verify metrics.",
      "feature_verify_started" to "Record start of a feature-verify session.",
      "feature_verify_workflow_continue" to "Continue durable bill-feature-verify workflow state.",
      "feature_verify_workflow_get" to "Fetch read-only full durable bill-feature-verify workflow state.",
      "feature_verify_workflow_latest" to "Fetch the latest bill-feature-verify workflow.",
      "feature_verify_workflow_list" to "List bill-feature-verify workflows.",
      "feature_verify_workflow_open" to "Open durable bill-feature-verify workflow state.",
      "feature_verify_workflow_resume" to "Summarize bill-feature-verify workflow resume state.",
      "feature_verify_workflow_update" to
        "Update durable bill-feature-verify workflow state and return a compact acknowledgement.",
      "goal_stats" to "Show aggregate decomposed-goal runtime metrics.",
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
      "update_check" to "Check whether the installed skill-bill runtime is up to date.",
    )

  private val inputSchemas: Map<String, Map<String, Any?>> =
    mapOf(
      "feature_task_phase_complete" to objectSchema(
        required = listOf("workflow_id", "phase_id", "attempt", "value"),
        properties = mapOf(
          "workflow_id" to stringSchema(minLength = 1),
          "phase_id" to stringSchema(enum = listOf("preplan", "plan", "implement")),
          "attempt" to mapOf("type" to "integer", "minimum" to 1),
          "value" to stringSchema(minLength = 1),
          "prompt" to stringSchema(minLength = 1),
          "summary" to stringSchema(minLength = 1),
        ),
      ),
      "feature_task_phase_block" to objectSchema(
        required = listOf("workflow_id", "phase_id", "attempt", "reason"),
        properties = mapOf(
          "workflow_id" to stringSchema(minLength = 1),
          "phase_id" to stringSchema(enum = listOf("preplan", "plan", "implement", "audit")),
          "attempt" to mapOf("type" to "integer", "minimum" to 1),
          "reason" to stringSchema(minLength = 1),
          "failure_disposition" to stringSchema(minLength = 1),
        ),
      ),
      "feature_task_audit_settle" to objectSchema(
        required = listOf("workflow_id", "attempt", "verdict", "value"),
        properties = mapOf(
          "workflow_id" to stringSchema(minLength = 1),
          "phase_id" to stringSchema(enum = listOf("audit")),
          "attempt" to mapOf("type" to "integer", "minimum" to 1),
          "verdict" to stringSchema(enum = listOf("satisfied", "gaps_found")),
          "value" to stringSchema(minLength = 1),
          "summary" to stringSchema(minLength = 1),
        ),
      ),
      "feature_verify_started" to objectSchema(
        required = listOf("acceptance_criteria_count", "rollout_relevant", "spec_summary"),
        properties = mapOf(
          "acceptance_criteria_count" to integerSchema,
          "rollout_relevant" to booleanSchema,
          "spec_summary" to stringSchema(),
          "orchestrated" to booleanSchema,
        ),
      ),
      "feature_verify_finished" to objectSchema(
        required = listOf(
          "feature_flag_audit_performed",
          "review_iterations",
          "audit_result",
          "completion_status",
          "session_id",
          "gaps_found",
          "orchestrated",
          "acceptance_criteria_count",
          "rollout_relevant",
          "spec_summary",
          "duration_seconds",
        ),
        properties = mapOf(
          "feature_flag_audit_performed" to booleanSchema,
          "review_iterations" to integerSchema,
          "audit_result" to stringSchema(enum = listOf("all_pass", "had_gaps", "skipped")),
          "completion_status" to stringSchema(
            enum = listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error"),
          ),
          "history_relevance" to historySignalSchema,
          "history_helpfulness" to historySignalSchema,
          "session_id" to stringSchema(),
          "gaps_found" to arraySchema(stringSchema()),
          "orchestrated" to booleanSchema,
          "acceptance_criteria_count" to integerSchema,
          "rollout_relevant" to booleanSchema,
          "spec_summary" to stringSchema(),
          "duration_seconds" to integerSchema,
        ),
      ),
      "feature_verify_workflow_continue" to workflowIdSchema(),
      "feature_verify_workflow_get" to workflowIdSchema(),
      "feature_verify_workflow_latest" to emptyObjectSchema,
      "feature_verify_workflow_list" to workflowListSchema(),
      "feature_verify_workflow_open" to workflowOpenSchema(),
      "feature_verify_workflow_resume" to workflowIdSchema(),
      "feature_verify_workflow_update" to workflowUpdateSchema(
        workflowStatusEnum = listOf("pending", "running", "completed", "failed", "abandoned"),
        stepIdEnum = listOf(
          "collect_inputs",
          "extract_criteria",
          "gather_diff",
          "feature_flag_audit",
          "code_review",
          "unit_test_value_check",
          "completeness_audit",
          "verdict",
          "finish",
        ),
      ),
      "goal_stats" to goalStatsSchema(),
      "import_review" to objectSchema(
        required = listOf("review_text"),
        properties = mapOf(
          "review_text" to stringSchema(),
          "orchestrated" to booleanSchema,
        ),
      ),
      "new_skill_scaffold" to objectSchema(
        required = listOf("payload"),
        properties = mapOf(
          "payload" to freeObjectSchema,
          "dry_run" to booleanSchema,
          "orchestrated" to booleanSchema,
        ),
      ),
      "pr_description_generated" to objectSchema(
        required = listOf(
          "commit_count",
          "files_changed_count",
          "was_edited_by_user",
          "pr_created",
          "pr_title",
          "orchestrated",
        ),
        properties = mapOf(
          "commit_count" to integerSchema,
          "files_changed_count" to integerSchema,
          "was_edited_by_user" to booleanSchema,
          "pr_created" to booleanSchema,
          "pr_title" to stringSchema(),
          "orchestrated" to booleanSchema,
          "generated_description" to stringSchema(),
          "final_pr_body" to stringSchema(),
        ),
      ),
      "quality_check_finished" to objectSchema(
        required = listOf(
          "final_failure_count",
          "iterations",
          "result",
          "session_id",
          "failing_check_names",
          "unsupported_reason",
          "orchestrated",
          "routed_skill",
          "detected_stack",
          "fallback",
          "scope_type",
          "initial_failure_count",
          "duration_seconds",
        ),
        properties = mapOf(
          "final_failure_count" to integerSchema,
          "iterations" to integerSchema,
          "result" to stringSchema(enum = listOf("pass", "fail", "skipped", "unsupported_stack")),
          "session_id" to stringSchema(),
          "failing_check_names" to arraySchema(stringSchema()),
          "unsupported_reason" to stringSchema(),
          "orchestrated" to booleanSchema,
          "routed_skill" to stringSchema(minLength = 1),
          "detected_stack" to stringSchema(minLength = 1),
          "fallback" to booleanSchema,
          "fallback_reason" to stringSchema(),
          "scope_type" to qualityCheckScopeSchema,
          "initial_failure_count" to integerSchema,
          "duration_seconds" to integerSchema,
        ),
      ),
      "quality_check_started" to objectSchema(
        required = listOf(
          "routed_skill",
          "detected_stack",
          "fallback",
          "scope_type",
          "initial_failure_count",
          "orchestrated",
        ),
        properties = mapOf(
          "routed_skill" to stringSchema(),
          "detected_stack" to stringSchema(),
          "fallback" to booleanSchema,
          "fallback_reason" to stringSchema(),
          "scope_type" to qualityCheckScopeSchema,
          "initial_failure_count" to integerSchema,
          "orchestrated" to booleanSchema,
        ),
      ),
      "resolve_learnings" to objectSchema(
        properties = mapOf(
          "repo" to stringSchema(),
          "skill" to stringSchema(),
          "review_session_id" to stringSchema(),
        ),
      ),
      "triage_findings" to objectSchema(
        required = listOf("review_run_id", "decisions"),
        properties = mapOf(
          "review_run_id" to stringSchema(),
          "decisions" to arraySchema(stringSchema()),
          "orchestrated" to booleanSchema,
        ),
      ),
      "telemetry_remote_stats" to remoteStatsSchema(),
    )

  val tools: List<McpToolSpec> =
    toolNames.map { name ->
      McpToolSpec(name, descriptions.getValue(name), inputSchemas[name] ?: McpToolSpec.openObjectSchema())
    }

  fun toolNamed(name: String): McpToolSpec? = tools.firstOrNull { it.name == name }
}
