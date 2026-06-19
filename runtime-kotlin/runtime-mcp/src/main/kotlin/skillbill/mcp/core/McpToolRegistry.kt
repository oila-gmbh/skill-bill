package skillbill.mcp.core

import skillbill.application.telemetry.specInputTypes
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

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
      "feature_task_finished",
      "feature_task_started",
      "feature_task_stats",
      "feature_task_workflow_get",
      "feature_task_workflow_latest",
      "feature_task_workflow_list",
      "feature_task_workflow_continue",
      "feature_task_workflow_open",
      "feature_task_workflow_resume",
      "feature_task_workflow_update",
      "feature_task_runtime_finished",
      "feature_task_runtime_started",
      "feature_task_runtime_stats",
      "feature_task_runtime_workflow_get",
      "feature_task_runtime_workflow_latest",
      "feature_task_runtime_workflow_list",
      "feature_task_runtime_workflow_continue",
      "feature_task_runtime_workflow_open",
      "feature_task_runtime_workflow_resume",
      "feature_task_runtime_workflow_update",
      "goal_stats",
      "import_review",
      "new_skill_scaffold",
      "pr_description_generated",
      "quality_check_finished",
      "quality_check_started",
      "readian_auth_status",
      "readian_get_article",
      "readian_get_articles_for_topic_query",
      "readian_get_spotlight",
      "readian_mark_story_status",
      "readian_save_candidate",
      "resolve_learnings",
      "review_stats",
      "telemetry_proxy_capabilities",
      "telemetry_remote_stats",
      "triage_findings",
    )

  private val descriptions: Map<String, String> =
    mapOf(
      "doctor" to "Check skill-bill installation health.",
      "feature_implement_finished" to
        "Record completion of a feature-implement session. Deprecated: prefer the feature_task_* family.",
      "feature_implement_stats" to
        "Show aggregate bill-feature-task metrics. Deprecated: prefer the feature_task_* family.",
      "feature_implement_started" to
        "Record start of a feature-implement session. Deprecated: prefer the feature_task_* family.",
      "feature_implement_workflow_continue" to
        "Continue durable bill-feature-task workflow state. Deprecated: prefer the feature_task_* family.",
      "feature_implement_workflow_get" to
        "Fetch read-only full durable bill-feature-task workflow state. Deprecated: prefer the feature_task_* family.",
      "feature_implement_workflow_latest" to
        "Fetch the latest bill-feature-task workflow. Deprecated: prefer the feature_task_* family.",
      "feature_implement_workflow_list" to
        "List bill-feature-task workflows. Deprecated: prefer the feature_task_* family.",
      "feature_implement_workflow_open" to
        "Open durable bill-feature-task workflow state. Deprecated: prefer the feature_task_* family.",
      "feature_implement_workflow_resume" to
        "Summarize bill-feature-task workflow resume state. Deprecated: prefer the feature_task_* family.",
      "feature_implement_workflow_update" to
        "Update durable bill-feature-task workflow state and return a compact acknowledgement. " +
        "Deprecated: prefer the feature_task_* family.",
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
      "feature_task_finished" to "Record completion of a feature-task session.",
      "feature_task_started" to "Record start of a feature-task session.",
      "feature_task_stats" to "Show aggregate feature-task metrics.",
      "feature_task_workflow_continue" to
        "Continue durable bill-feature-task runtime workflow state.",
      "feature_task_workflow_get" to
        "Fetch read-only full durable bill-feature-task runtime workflow state.",
      "feature_task_workflow_latest" to "Fetch the latest bill-feature-task runtime workflow.",
      "feature_task_workflow_list" to "List bill-feature-task runtime workflows.",
      "feature_task_workflow_open" to "Open durable bill-feature-task runtime workflow state.",
      "feature_task_workflow_resume" to
        "Summarize bill-feature-task runtime workflow resume state.",
      "feature_task_workflow_update" to
        "Update durable bill-feature-task runtime workflow state and return a compact acknowledgement.",
      "feature_task_runtime_finished" to
        "Deprecated alias for feature_task_finished. Use feature_task_finished.",
      "feature_task_runtime_started" to
        "Deprecated alias for feature_task_started. Use feature_task_started.",
      "feature_task_runtime_stats" to
        "Deprecated alias for feature_task_stats. Use feature_task_stats.",
      "feature_task_runtime_workflow_continue" to
        "Deprecated alias for feature_task_workflow_continue. Use feature_task_workflow_continue.",
      "feature_task_runtime_workflow_get" to
        "Deprecated alias for feature_task_workflow_get. Use feature_task_workflow_get.",
      "feature_task_runtime_workflow_latest" to
        "Deprecated alias for feature_task_workflow_latest. Use feature_task_workflow_latest.",
      "feature_task_runtime_workflow_list" to
        "Deprecated alias for feature_task_workflow_list. Use feature_task_workflow_list.",
      "feature_task_runtime_workflow_open" to
        "Deprecated alias for feature_task_workflow_open. Use feature_task_workflow_open.",
      "feature_task_runtime_workflow_resume" to
        "Deprecated alias for feature_task_workflow_resume. Use feature_task_workflow_resume.",
      "feature_task_runtime_workflow_update" to
        "Deprecated alias for feature_task_workflow_update. Use feature_task_workflow_update.",
      "goal_stats" to "Show aggregate decomposed-goal runtime metrics.",
      "import_review" to "Import code review output into the local telemetry store.",
      "new_skill_scaffold" to "Scaffold a new skill from a validated payload.",
      "pr_description_generated" to "Record PR description generation telemetry.",
      "quality_check_finished" to "Record completion of a quality-check session.",
      "quality_check_started" to "Record start of a quality-check session.",
      "readian_auth_status" to "Report whether the Readian MCP boundary has an authenticated session.",
      "readian_get_article" to "Fetch a Readian article through the authenticated MCP boundary.",
      "readian_get_articles_for_topic_query" to
        "Fetch Readian articles for a topic query through the authenticated MCP boundary.",
      "readian_get_spotlight" to "Fetch Readian Spotlight articles through the authenticated MCP boundary.",
      "readian_mark_story_status" to "Mark story status through the authenticated Readian MCP boundary.",
      "readian_save_candidate" to "Save an editorial candidate through the authenticated Readian MCP boundary.",
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
          "source" to stringSchema(enum = listOf("production", "test", "synthetic", "unknown")),
          "acceptance_criteria_count" to integerSchema,
          "open_questions_count" to integerSchema,
          "spec_input_types" to arraySchema(stringSchema(enum = specInputTypes)),
          "spec_word_count" to integerSchema,
          "rollout_needed" to booleanSchema,
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
          "source" to stringSchema(enum = listOf("production", "test", "synthetic", "unknown")),
          "completion_status" to stringSchema(
            enum = listOf(
              "completed",
              "abandoned_at_planning",
              "abandoned_at_implementation",
              "abandoned_at_review",
              "error",
            ),
          ),
          "plan_correction_count" to integerSchema,
          "plan_task_count" to integerSchema,
          "plan_phase_count" to integerSchema,
          "feature_flag_used" to booleanSchema,
          "files_created" to integerSchema,
          "files_modified" to integerSchema,
          "tasks_completed" to integerSchema,
          "review_iterations" to integerSchema,
          "audit_result" to stringSchema(enum = listOf("all_pass", "had_gaps", "skipped", "not_reached")),
          "audit_iterations" to integerSchema,
          "validation_result" to stringSchema(enum = listOf("pass", "fail", "skipped", "not_reached")),
          "boundary_history_written" to booleanSchema,
          "pr_created" to booleanSchema,
          "feature_flag_pattern" to stringSchema(enum = listOf("simple_conditional", "di_switch", "legacy", "none")),
          "boundary_history_value" to stringSchema(enum = listOf("none", "irrelevant", "low", "medium", "high")),
          "plan_deviation_notes" to stringSchema(),
          "child_steps" to arraySchema(featureImplementChildStepSchema()),
        ),
      ),
      "feature_implement_workflow_continue" to objectSchema(
        properties = mapOf(
          "workflow_id" to stringSchema(),
          "issue_key" to stringSchema(),
          "subtask_id" to integerSchema,
        ),
      ),
      "feature_implement_workflow_get" to workflowIdSchema(),
      "feature_implement_workflow_latest" to emptyObjectSchema,
      "feature_implement_workflow_list" to workflowListSchema(),
      "feature_implement_workflow_open" to workflowOpenSchema(),
      "feature_implement_workflow_resume" to workflowIdSchema(),
      "feature_implement_workflow_update" to workflowUpdateSchema(
        workflowStatusEnum = listOf("pending", "running", "completed", "failed", "abandoned", "blocked"),
        stepIdEnum = listOf(
          "assess",
          "create_branch",
          "preplan",
          "plan",
          "implement",
          "review",
          "audit",
          "validate",
          "write_history",
          "commit_push",
          "pr_description",
          "finish",
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
          "completeness_audit",
          "verdict",
          "finish",
        ),
      ),
      "feature_task_started" to objectSchema(
        required = listOf(
          "feature_size",
          "issue_key",
          "feature_name",
        ),
        properties = mapOf(
          "feature_size" to stringSchema(enum = listOf("SMALL", "MEDIUM", "LARGE")),
          "issue_key" to stringSchema(),
          "feature_name" to stringSchema(),
          "session_id" to stringSchema(),
        ),
      ),
      "feature_task_finished" to objectSchema(
        required = listOf(
          "session_id",
          "completion_status",
          "completed_phase_ids",
        ),
        properties = mapOf(
          "session_id" to stringSchema(),
          "completion_status" to stringSchema(
            enum = listOf("completed", "blocked", "decomposed_at_planning", "error"),
          ),
          "completed_phase_ids" to arraySchema(stringSchema()),
          "phase_outcomes" to freeObjectSchema,
          "review_fix_iteration_count" to integerSchema,
          "audit_gap_iteration_count" to integerSchema,
          "last_incomplete_phase" to stringSchema(),
          "blocked_reason" to stringSchema(),
          "resolved_branch" to stringSchema(),
        ),
      ),
      "feature_task_workflow_continue" to workflowIdSchema(),
      "feature_task_workflow_get" to workflowIdSchema(),
      "feature_task_workflow_latest" to emptyObjectSchema,
      "feature_task_workflow_list" to workflowListSchema(),
      "feature_task_workflow_open" to workflowOpenSchema(),
      "feature_task_workflow_resume" to workflowIdSchema(),
      "feature_task_workflow_update" to workflowUpdateSchema(
        workflowStatusEnum = listOf("pending", "running", "completed", "failed", "abandoned", "blocked"),
        stepIdEnum = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds,
      ),
      "feature_task_runtime_started" to objectSchema(
        required = listOf(
          "feature_size",
          "issue_key",
          "feature_name",
        ),
        properties = mapOf(
          "feature_size" to stringSchema(enum = listOf("SMALL", "MEDIUM", "LARGE")),
          "issue_key" to stringSchema(),
          "feature_name" to stringSchema(),
          "session_id" to stringSchema(),
        ),
      ),
      "feature_task_runtime_finished" to objectSchema(
        required = listOf(
          "session_id",
          "completion_status",
          "completed_phase_ids",
        ),
        properties = mapOf(
          "session_id" to stringSchema(),
          "completion_status" to stringSchema(
            enum = listOf("completed", "blocked", "decomposed_at_planning", "error"),
          ),
          "completed_phase_ids" to arraySchema(stringSchema()),
          "phase_outcomes" to freeObjectSchema,
          "review_fix_iteration_count" to integerSchema,
          "audit_gap_iteration_count" to integerSchema,
          "last_incomplete_phase" to stringSchema(),
          "blocked_reason" to stringSchema(),
          "resolved_branch" to stringSchema(),
        ),
      ),
      "feature_task_runtime_workflow_continue" to workflowIdSchema(),
      "feature_task_runtime_workflow_get" to workflowIdSchema(),
      "feature_task_runtime_workflow_latest" to emptyObjectSchema,
      "feature_task_runtime_workflow_list" to workflowListSchema(),
      "feature_task_runtime_workflow_open" to workflowOpenSchema(),
      "feature_task_runtime_workflow_resume" to workflowIdSchema(),
      "feature_task_runtime_workflow_update" to workflowUpdateSchema(
        workflowStatusEnum = listOf("pending", "running", "completed", "failed", "abandoned", "blocked"),
        stepIdEnum = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds,
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
          "routed_skill" to stringSchema(),
          "detected_stack" to stringSchema(),
          "scope_type" to qualityCheckScopeSchema,
          "initial_failure_count" to integerSchema,
          "duration_seconds" to integerSchema,
        ),
      ),
      "quality_check_started" to objectSchema(
        required = listOf("routed_skill", "detected_stack", "scope_type", "initial_failure_count", "orchestrated"),
        properties = mapOf(
          "routed_skill" to stringSchema(),
          "detected_stack" to stringSchema(),
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
      "readian_get_article" to passthroughObjectSchema(
        required = listOf("article_id"),
        properties = mapOf(
          "article_id" to stringSchema(),
        ),
      ),
      "readian_get_articles_for_topic_query" to passthroughObjectSchema(
        required = listOf("topic_query"),
        properties = mapOf(
          "topic_query" to stringSchema(),
          "date" to stringSchema(),
          "start_date" to stringSchema(),
          "end_date" to stringSchema(),
          "subscribed_only" to booleanSchema,
          "limit" to integerSchema,
        ),
      ),
      "readian_get_spotlight" to passthroughObjectSchema(
        properties = mapOf(
          "date" to stringSchema(),
          "limit" to integerSchema,
        ),
      ),
      "readian_save_candidate" to passthroughObjectSchema(
        required = listOf("candidate_id"),
        properties = mapOf(
          "candidate_id" to stringSchema(),
          "notes" to stringSchema(),
        ),
      ),
      "readian_mark_story_status" to passthroughObjectSchema(
        required = listOf("story_id", "status"),
        properties = mapOf(
          "story_id" to stringSchema(),
          "status" to stringSchema(),
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
