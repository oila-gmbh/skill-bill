package skillbill.review.context.model

object ReviewPacketConsumerContract {
  const val SOURCE_PATH: String = "orchestration/review-orchestrator/specialist-contract.md"
  const val SECTION_HEADING: String = "## Packet Consumer Contract"

  val FORBIDDEN_REDISCOVERY: List<String> = listOf(
    "review_status",
    "review_scope",
    "base_head_revision_discovery",
    "diff_recomputation",
    "dominant_stack_routing",
    "platform_pack_and_addon_resolution",
    "project_guidance_traversal",
    "learnings_resolution",
    "build_test_fact_discovery",
    "telemetry_ownership_determination",
    "broad_repository_search",
    "unrelated_rubric_read",
    "unassigned_file_access",
    "unselected_mcp_tool_call",
    "unscoped_shell_command",
  )
}
