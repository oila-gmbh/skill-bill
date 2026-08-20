package skillbill.review.context.model

object ReviewPacketConsumerContract {
  const val SOURCE_PATH: String = "orchestration/review-orchestrator/specialist-contract.md"
  const val RESOURCE_PATH: String = "skillbill/review/specialist-contract.md"
  const val SPECIALIST_RULES_HEADING: String = "## Shared Contract For Every Specialist"
  const val SECTION_HEADING: String = "## Packet Consumer Contract"
  const val REPORT_STRUCTURE_HEADING: String = "## Shared Report Structure"

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
    "rubric_rediscovery",
    "unassigned_file_access",
    "unselected_mcp_tool_call",
    "unscoped_shell_command",
    "diff_artifact_rediscovery",
    "scratch_path_rediscovery",
    "contract_rediscovery",
    "rules_rediscovery",
    "repeated_evidence_read",
    "per_commit_stepping",
    "worker_relevance_redecision",
    "aggregate_diff_restart",
  )

  const val AUTHORITATIVE_LAUNCH_CONTRACT: String =
    "Consume only the immutable lane projection supplied at launch. Do not rediscover, widen, " +
      "recompute, or read sibling-lane or parent review context. Review the whole assembled bundle " +
      "in one operation; commit order is readable metadata to relate earlier and later commits within " +
      "that pass. Do not step commit-by-commit, re-decide relevance, or restart from an aggregate diff."
  const val CONSUMER_CONTRACT: String = AUTHORITATIVE_LAUNCH_CONTRACT

  /**
   * The integration pass runs once, after every specialist lane has finished. It looks only for
   * behavior that emerges from how the commits combine — no lane rubric is re-run here, and a
   * defect wholly inside one commit is already the finishing lane's to report.
   */
  const val INTEGRATION_CONTRACT: String =
    "You are the single final integration pass over a commit sequence every specialist lane has " +
      "already reviewed. Report only cross-commit behavior: an interaction, ordering dependency, " +
      "or contract drift that no single commit shows on its own. Do not re-run any specialist " +
      "rubric, do not re-review a single commit in isolation, and do not restate a lane's finding. " +
      "Cite the involved commits in the commits= segment of every finding you report. Lanes " +
      "reported as incomplete coverage stay incomplete: this pass does not review what they left " +
      "unreviewed and must never be described as closing that gap."

  const val EVIDENCE_SURFACE_RULES: String =
    "Use only the measured evidence broker. Assigned evidence is limited to projected hunk windows. " +
      "A complete-file expansion requires a launch-authorized record with a nonblank reachability reason. " +
      "Each normalized evidence target may be read once. " +
      "Read an assigned repository-relative path; an evidence_locator store_path or payload_file " +
      "identifies a hunk inside the broker's store and is refused as a read argument."

  const val INLINE_VERIFICATION_EVIDENCE_SURFACE: String =
    "Cited region and direct callers only. Do not expand through the evidence broker or the expansion ledger."

  const val DELEGATED_VERIFICATION_EVIDENCE_SURFACE: String =
    "$EVIDENCE_SURFACE_RULES Expansion through the existing bounded evidence broker and expansion ledger is permitted."

  const val ADJUDICATION_EVIDENCE_SURFACE: String =
    "Cited region, the stage-1 verdict, and the supplied spec intent projection only. " +
      "Do not inspect sibling findings. Do not re-test whether the claim is true. " +
      "Review is read-only: do not build, compile, or run tests."

  const val REPORT_STRUCTURE: String =
    "- [F-001] <Severity> | <Confidence> | <file:line> | <description>\n" +
      "Findings naming commits use: - [F-001] <Severity> | <Confidence> | commits=<sha>[,<sha>] | " +
      "<file:line> | <description>"

  fun authoritativeSpecialistContract(source: String): String {
    val normalized = source.replace("\r\n", "\n")
    return listOf(SPECIALIST_RULES_HEADING, REPORT_STRUCTURE_HEADING)
      .joinToString("\n\n") { heading -> normalized.requireSection(heading) }
  }

  private fun String.requireSection(heading: String): String {
    val body = substringAfter("$heading\n", "")
    require(body.isNotEmpty()) {
      "Packaged authoritative delegated-review specialist contract is missing section '$heading'."
    }
    return "$heading\n${body.substringBefore("\n## ").trim()}"
  }
}
