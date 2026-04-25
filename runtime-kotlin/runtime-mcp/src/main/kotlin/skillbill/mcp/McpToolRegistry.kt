package skillbill.mcp

data class McpToolSpec(
  val name: String,
  val description: String,
) {
  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "name" to name,
    "description" to description,
    "inputSchema" to mapOf(
      "type" to "object",
      "additionalProperties" to true,
    ),
  )
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

  val tools: List<McpToolSpec> =
    toolNames.map { name ->
      McpToolSpec(name, descriptions.getValue(name))
    }
}
