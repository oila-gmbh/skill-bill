package skillbill.review.context.model

enum class ReviewOperationKind { FILE_READ, SHELL_COMMAND, SEARCH, MCP_TOOL, RUBRIC_READ }

data class ReviewRequestedOperation(
  val kind: ReviewOperationKind,
  val target: String,
  val reachabilityReason: String? = null,
) {
  init {
    require(target.isNotBlank()) { "Requested review operation target must not be blank." }
  }
}

data class ForbiddenReviewOperation(val category: String, val target: String, val reason: String) {
  init {
    require(category.isNotBlank() && target.isNotBlank() && reason.isNotBlank()) {
      "Forbidden review operation must carry a category, target, and reason."
    }
  }
}

/**
 * Decides, without consulting any platform, pack, or provider identity, whether an operation a
 * specialist wants to run stays inside its assignment. Everything the parent already resolved is
 * forbidden to rediscover; everything outside the assignment needs an authorized expansion.
 */
class ReviewOperationPolicy(
  private val assignment: ReviewAssignment,
  private val laneRubricId: String,
  private val namedDependencies: Set<String> = emptySet(),
) {
  private val assignedPaths: Set<String> = assignment.assignedPaths.map { it.replace('\\', '/') }.toSet()

  private val reachablePaths: Set<String> = assignedPaths +
    assignment.dependencyAllowlist.normalized +
    namedDependencies.map { it.replace('\\', '/') }

  fun classify(operation: ReviewRequestedOperation): ForbiddenReviewOperation? = when (operation.kind) {
    ReviewOperationKind.SHELL_COMMAND -> classifyShell(operation.target)
    ReviewOperationKind.SEARCH -> classifySearch(operation.target)
    ReviewOperationKind.MCP_TOOL -> classifyMcpTool(operation.target)
    ReviewOperationKind.RUBRIC_READ -> classifyRubric(operation.target)
    ReviewOperationKind.FILE_READ -> classifyFileRead(operation)
  }

  fun isReachable(path: String): Boolean = path.replace('\\', '/') in reachablePaths

  fun isAssigned(path: String): Boolean = path.replace('\\', '/') in assignedPaths

  private fun classifyShell(command: String): ForbiddenReviewOperation? {
    val normalized = command.trim().lowercase()
    SHELL_REDISCOVERY.forEach { (prefixes, category) ->
      if (prefixes.any { normalized == it || normalized.startsWith("$it ") }) {
        return forbidden(category, command, "The parent packet already carries this fact; rediscovery is forbidden.")
      }
    }
    if (SEARCH_COMMANDS.any { normalized == it || normalized.startsWith("$it ") }) {
      return classifySearch(command)
    }
    return forbidden(
      "unscoped_shell_command",
      command,
      "A bounded specialist runs no shell command outside its measured evidence surface.",
    )
  }

  private fun classifySearch(target: String): ForbiddenReviewOperation? {
    val scoped = SEARCH_SCOPE_PATTERN.find(target)?.value?.trim('"', '\'')
    return if (scoped != null && isReachable(scoped)) {
      null
    } else {
      forbidden(
        "broad_repository_search",
        target,
        "Searches are limited to the paths this assignment owns or names as a dependency.",
      )
    }
  }

  private fun classifyMcpTool(tool: String): ForbiddenReviewOperation? {
    val normalized = tool.trim().lowercase()
    MCP_REDISCOVERY.forEach { (fragments, category) ->
      if (fragments.any { it in normalized }) {
        return forbidden(category, tool, "The parent packet already resolved this; rediscovery is forbidden.")
      }
    }
    return forbidden(
      "unselected_mcp_tool_call",
      tool,
      "A bounded specialist calls no tool the parent did not project into its assignment.",
    )
  }

  private fun classifyRubric(rubricId: String): ForbiddenReviewOperation? = if (rubricId == laneRubricId) {
    null
  } else {
    forbidden(
      "unrelated_rubric_read",
      rubricId,
      "Lane '${assignment.lane}' owns exactly one rubric, '$laneRubricId'.",
    )
  }

  private fun classifyFileRead(operation: ReviewRequestedOperation): ForbiddenReviewOperation? {
    val path = operation.target.replace('\\', '/')
    val guidanceViolation = GUIDANCE_FILE_NAMES.firstOrNull { path == it || path.endsWith("/$it") }?.let {
      forbidden(
        "project_guidance_traversal",
        operation.target,
        "Project guidance reaches a specialist only as packet-attested matched rules.",
      )
    }
    val routingViolation = ROUTING_PATH_FRAGMENTS.firstNotNullOfOrNull { (fragments, category) ->
      category.takeIf { fragments.any { it in path } }?.let {
        forbidden(it, operation.target, "The parent packet already resolved this routing decision.")
      }
    }
    return when {
      path in assignedPaths -> null
      guidanceViolation != null -> guidanceViolation
      routingViolation != null -> routingViolation
      isReachable(path) && !operation.reachabilityReason.isNullOrBlank() -> null
      else -> forbidden(
        "unassigned_file_access",
        operation.target,
        "A reachability reason documents an assignment-authorized dependency; it cannot authorize a new path.",
      )
    }
  }

  private fun forbidden(category: String, target: String, reason: String) =
    ForbiddenReviewOperation(category, target, reason)

  private companion object {
    val SHELL_REDISCOVERY: List<Pair<List<String>, String>> = listOf(
      listOf("git status", "git stash list") to "review_status",
      listOf("gh pr diff", "gh pr view", "gh pr list") to "review_scope",
      listOf("git merge-base", "git rev-parse", "git symbolic-ref", "git branch") to "base_head_revision_discovery",
      listOf("git diff", "git show", "git log") to "diff_recomputation",
      listOf("./gradlew", "gradle", "npm test", "npm run", "cargo test", "cargo build", "pytest", "go test")
        to "build_test_fact_discovery",
      listOf("skill-bill validate", "skill-bill show", "skill-bill explain") to "platform_pack_and_addon_resolution",
    )

    val MCP_REDISCOVERY: List<Pair<List<String>, String>> = listOf(
      listOf("resolve_learnings", "learnings") to "learnings_resolution",
      listOf("telemetry", "review_stats") to "telemetry_ownership_determination",
      listOf("stack_routing", "detect_stack") to "dominant_stack_routing",
    )

    val ROUTING_PATH_FRAGMENTS: List<Pair<List<String>, String>> = listOf(
      listOf("platform-packs/", "platform.yaml") to "platform_pack_and_addon_resolution",
      listOf("stack-routing", "orchestration/routing") to "dominant_stack_routing",
      listOf("telemetry-contract") to "telemetry_ownership_determination",
    )

    val GUIDANCE_FILE_NAMES: List<String> =
      listOf("AGENTS.md", "CLAUDE.md", "AGENT.md", "GEMINI.md", ".cursorrules", "CONVENTIONS.md")

    val SEARCH_COMMANDS: List<String> = listOf("grep", "rg", "find", "fd", "ls", "glob", "ack")

    val SEARCH_SCOPE_PATTERN = Regex("""[^\s"']*/[^\s"']*""")
  }
}
