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
    "diff_artifact_rediscovery",
    "scratch_path_rediscovery",
    "contract_rediscovery",
    "rules_rediscovery",
    "repeated_evidence_read",
  )

  const val CONSUMER_CONTRACT: String =
    "Consume only the immutable lane projection supplied at launch. Do not rediscover, widen, " +
      "recompute, or read sibling-lane or parent review context."

  const val EVIDENCE_SURFACE_RULES: String =
    "Use only the measured evidence broker. Assigned evidence is limited to projected hunk windows. " +
      "A complete-file expansion requires a launch-authorized record with a nonblank reachability reason. " +
      "Each normalized evidence target may be read once."

  const val REPORT_STRUCTURE: String =
    "[F-NNN] Severity | Confidence | optional specialist=<exact identity> | " +
      "path=<JSON string> | line=<positive integer> | description"
}
