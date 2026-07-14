package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.workflow.repoRoot
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.context.GovernedReviewLaunch
import skillbill.review.context.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.ProviderTokenUsage
import skillbill.review.context.ReviewAssignment
import skillbill.review.context.ReviewChangedHunk
import skillbill.review.context.ReviewContextBudgetPolicy
import skillbill.review.context.ReviewContextPacket
import skillbill.review.context.TokenOwnership
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Inject
class ParallelCodeReviewRunner(
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val diffResolver: DiffResolverPort,
  private val parallelLaneRunner: ParallelReviewLaneRunner,
) {
  fun run(request: ParallelCodeReviewRequest): ParallelCodeReviewResult {
    val agent1 = resolveAgent(request.agent1Id, "--agent1")
    val agent2 = resolveAgent(request.agent2Id, "--agent2")
    if (agent1.id == agent2.id) {
      throw UsageValidationException(
        "agent1 and agent2 must be different agents; both resolved to '${agent1.id}'.",
      )
    }

    val diffText = resolveDiff(request)
    val stack = detectStack(diffText, request.repoRoot)
    val packet = preparePacket(request, stack, diffText, listOf(agent1.id, agent2.id))
    val prompt1 = buildPrompt(packet, agent1.id, request.codeReviewMode)
    val prompt2 = buildPrompt(packet, agent2.id, request.codeReviewMode)

    val timeoutSec = request.timeout?.inWholeSeconds ?: (DEFAULT_TIMEOUT_MINUTES * SECONDS_PER_MINUTE)
    val laneRunResult = parallelLaneRunner.runTwoLanes(
      ParallelReviewLaneRunRequest(
        lane1 = { launchLane(agent1.id, prompt1, request) },
        lane2 = { launchLane(agent2.id, prompt2, request, request.agent2Model) },
        timeout = (timeoutSec + TIMEOUT_BUFFER_SECONDS).seconds,
      ),
    )
    val outcome1 = laneRunResult.lane1
    val outcome2 = laneRunResult.lane2

    val lane1Result = ParallelReviewLaneResult(
      agentId = agent1.id,
      findings = if (outcome1.success) ParallelReviewFindingParser.parse(outcome1.rawOutput) else emptyList(),
    )
    val lane2Result = ParallelReviewLaneResult(
      agentId = agent2.id,
      findings = if (outcome2.success) ParallelReviewFindingParser.parse(outcome2.rawOutput) else emptyList(),
    )

    val mergeResult = ParallelReviewMerger.merge(lane1Result, lane2Result)
    return ParallelCodeReviewResult(
      mergeResult = mergeResult,
      lane1 = ParallelReviewLaneStatus(agent1.id, outcome1.success, outcome1.failureReason, outcome1.tokenUsage),
      lane2 = ParallelReviewLaneStatus(agent2.id, outcome2.success, outcome2.failureReason, outcome2.tokenUsage),
    )
  }

  private fun resolveAgent(agentId: String, label: String): InstallAgent {
    if (agentId.isBlank()) {
      throw UsageValidationException(
        "Option $label is required. Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
      )
    }
    return try {
      InstallAgent.fromNormalizedId(agentId, label = label)
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
      throw UsageValidationException(
        "Unsupported agent '$agentId' for $label. Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
      )
    }
  }

  private fun resolveDiff(request: ParallelCodeReviewRequest): String {
    val diffText = request.suppliedDiff ?: request.suppliedDiffPath?.let { path ->
      diffResolver.readDiff(path, MAX_SUPPLIED_DIFF_BYTES)
        ?: throw DiffResolutionException(
          "--diff-file must name a readable, non-empty regular file no larger than $MAX_SUPPLIED_DIFF_BYTES bytes.",
        )
    } ?: when (request.scope) {
      ParallelReviewScope.STAGED -> runDiff(listOf("git", "diff", "--cached"), request.repoRoot)
      ParallelReviewScope.UNSTAGED -> runDiff(listOf("git", "diff"), request.repoRoot)
      ParallelReviewScope.BRANCH -> {
        val base = detectBranchBase(request.repoRoot)
        runDiff(listOf("git", "diff", "$base...HEAD"), request.repoRoot)
      }
      ParallelReviewScope.PR -> runDiff(listOf("gh", "pr", "diff"), request.repoRoot)
    }
    if (diffText.isBlank()) {
      throw DiffResolutionException("Diff is empty for scope '${request.scope.name.lowercase()}'.")
    }
    return diffText
  }

  private fun detectBranchBase(repoRoot: Path): String {
    val candidates = listOf("main", "master", "origin/main", "origin/master")
    for (candidate in candidates) {
      val result = diffResolver.runProcess(listOf("git", "merge-base", "HEAD", candidate), repoRoot)
      if (result != null) return result.trim()
    }
    throw DiffResolutionException(
      "Could not detect branch base. Tried: ${candidates.joinToString()}.",
    )
  }

  private fun runDiff(args: List<String>, workDir: Path): String = diffResolver.runProcess(args, workDir)
    ?: throw DiffResolutionException(
      "Command failed: ${args.joinToString(" ")}",
    )

  private fun detectStack(diffText: String, repoRoot: Path): String? {
    val packsRoot = repoRoot.resolve("platform-packs")
    // A missing platform-packs directory yields an empty list (no exception) and degrades to a
    // generic rubric. A directory that exists but is out of contract (corrupt platform.yaml,
    // invalid composition) throws; surface that loudly instead of silently dropping the
    // stack-specific specialists, per the shell's "never silently fall back" contract.
    val manifests = try {
      scaffoldCatalogService.discoverPlatformManifests(packsRoot)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      val displayPath = runCatching { repoRoot.relativize(packsRoot) }.getOrDefault(packsRoot)
      throw StackDetectionException(
        "Platform pack discovery failed for $displayPath: ${e.message ?: e.javaClass.simpleName}. " +
          "Repair the platform pack before running parallel review.",
        e,
      )
    }
    if (manifests.isEmpty()) return null

    val diffPaths = DIFF_PATH_PATTERN
      .findAll(diffText)
      .map { it.groupValues[1] }
      .filterNot(RoutingSignalPathMatcher::isIgnored)
      .toList()

    val scores = manifests.associateWith { manifest ->
      manifest.routingSignals.strong.sumOf { signal ->
        diffPaths.count { path -> RoutingSignalPathMatcher.matches(path, signal) }
      }
    }

    val best = scores.maxByOrNull { it.value }
    return if (best != null && best.value > 0) best.key.slug else null
  }

  private fun preparePacket(
    request: ParallelCodeReviewRequest,
    stack: String?,
    diffText: String,
    selectedLanes: List<String>,
  ): ReviewContextPacket {
    val normalizedDiff = diffText.replace("\r\n", "\n")
    val paths = DIFF_PATH_PATTERN.findAll(normalizedDiff).map { it.groupValues[1] }.distinct().sorted().toList()
    val digest = sha256(normalizedDiff)
    val baseRevision = if (request.suppliedDiff != null || request.suppliedDiffPath != null) "supplied-diff" else request.scope.name.lowercase()
    val headRevision = "working-tree"
    val packet = ReviewContextPacket(
      reviewId = "parallel-${digest.take(16)}",
      repositoryIdentity = request.repoRoot.toAbsolutePath().normalize().toString(),
      baseRevision = baseRevision,
      headRevision = headRevision,
      status = request.scope.name.lowercase(),
      stack = stack,
      pack = stack,
      addOns = emptyList(),
      selectedLanes = selectedLanes,
      changedHunks = paths.map { path -> ReviewChangedHunk(path, 0, 0, 0, 0, diffForPath(normalizedDiff, path)) },
    )
    require(packet.canonicalBytes <= ReviewContextBudgetPolicy.DEFAULT.maxParentPacketBytes) {
      "Review parent packet exceeds max_parent_packet_bytes."
    }
    return packet
  }

  private fun buildPrompt(
    packet: ReviewContextPacket,
    lane: String,
    codeReviewMode: skillbill.workflow.model.CodeReviewExecutionMode,
  ): String {
    val assignment = ReviewAssignment(
      reviewId = packet.reviewId,
      packetDigest = packet.digest,
      lane = lane,
      baseRevision = packet.baseRevision,
      headRevision = packet.headRevision,
      assignedPaths = packet.changedHunks.map { it.path },
      assignedHunks = packet.changedHunks.map { it.content },
      evidenceTargets = packet.changedHunks.map { it.path },
    )
    val launch = GovernedReviewLaunch(
      assignment = assignment,
      specialistContract = "Review only the bounded assignment and report concrete findings with provenance.",
      rubric = "Apply the routed ${packet.stack ?: "generic"} review rubric selected by bill-code-review.",
      brokerId = "parallel-${assignment.digest.take(16)}",
      budget = ReviewContextBudgetPolicy.DEFAULT,
    )
    if (lane == "codex") launch.requireCodexForkTurns("none")
    launch.budgetOutcomeOrNull()?.let { throw UsageValidationException("${it.type}: ${it.budgetKind} ${it.observedValue} > ${it.configuredLimit}") }
    return buildString {
      appendLine("Governed specialist review contract")
      appendLine("Execution mode: ${codeReviewMode.wireValue}")
      appendLine(
        "Return only a risk register in F-XXX bullet format, one finding per line: " +
          "- [F-NNN] Blocker|Major|Minor|Nit | High|Medium|Low | file:line | description",
      )
      appendLine()
      appendLine(launch.canonicalPayload)
      appendLine(
        "Read only these assigned paths and their direct dependencies. Do not run git status, git diff, " +
          "merge-base, stack discovery, or broad repository searches.",
      )
      appendLine("All assigned changed hunks are included above; unrelated diff is absent from this launch payload.")
    }
  }

  private fun diffForPath(diffText: String, path: String): String {
    val marker = "diff --git "
    return diffText.split(marker).firstOrNull { section -> section.contains("+++ b/$path\n") }
      ?.let { "$marker$it" }
      ?: diffText
  }

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

  private fun launchLane(
    agentId: String,
    prompt: String,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
  ): ParallelReviewLaneOutcome {
    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = agentId,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = "code-review-parallel",
          repoRoot = request.repoRoot,
          timeout = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
          promptOverride = prompt,
          modelOverride = modelOverride,
          conversationIsolation = ConversationIsolation.NONE,
        ),
      ),
    )
    return when (outcome) {
      is AgentRunLaunchFacts -> {
        val resultBytes = outcome.stdout.toByteArray(StandardCharsets.UTF_8).size.toLong()
        val reason = laneFailureReason(outcome) ?: if (resultBytes > ReviewContextBudgetPolicy.DEFAULT.maxLaneResultBytes) {
          "$REVIEW_CONTEXT_BUDGET_EXCEEDED: lane_result_bytes $resultBytes > ${ReviewContextBudgetPolicy.DEFAULT.maxLaneResultBytes}"
        } else {
          null
        }
        val usage = if (listOf(
            outcome.inputTokens,
            outcome.cachedInputTokens,
            outcome.outputTokens,
            outcome.reasoningTokens,
            outcome.totalTokens,
          ).any { it != null }
        ) {
          ProviderTokenUsage(
            inputTokens = outcome.inputTokens,
            cachedInputTokens = outcome.cachedInputTokens,
            outputTokens = outcome.outputTokens,
            reasoningTokens = outcome.reasoningTokens,
            totalTokens = outcome.totalTokens,
            ownership = if (outcome.tokenOwnership == "inclusive") TokenOwnership.INCLUSIVE else TokenOwnership.DIRECT,
          )
        } else {
          null
        }
        ParallelReviewLaneOutcome(success = reason == null, rawOutput = outcome.stdout, failureReason = reason, tokenUsage = usage)
      }
      is UnsupportedAgentRunLaunch ->
        ParallelReviewLaneOutcome(
          success = false,
          rawOutput = "",
          failureReason = "unsupported agent: ${outcome.reason}",
        )
    }
  }

  // Maps a completed launch to a human-readable failure reason, or null when the lane succeeded.
  // timedOut/spawnFailed/interrupted are checked first because they leave exitStatus null.
  // The null == exitStatus guard closes the degenerate case where all flags are false but
  // exitStatus is also null — a combination the init requires prevent but that would otherwise
  // fall through to else->null and silently report an empty-findings lane as succeeded.
  private fun laneFailureReason(facts: AgentRunLaunchFacts): String? = when {
    facts.timedOut -> "agent timed out"
    facts.spawnFailed -> "agent process failed to spawn"
    facts.interrupted -> "agent was interrupted"
    facts.exitStatus == null -> "agent exited with unknown status"
    facts.exitStatus != 0 -> buildString {
      append("agent exited with status ${facts.exitStatus}")
      facts.stderr.trim().lineSequence().firstOrNull { it.isNotBlank() }?.let { line ->
        append(" — ${line.take(STDERR_EXCERPT_MAX_LENGTH)}")
      }
    }
    else -> null
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MINUTES = 30L
    const val TIMEOUT_BUFFER_SECONDS = 30L
    const val SECONDS_PER_MINUTE = 60L
    const val STDERR_EXCERPT_MAX_LENGTH = 120
    const val MAX_SUPPLIED_DIFF_BYTES = 1_000_000L
    val DIFF_PATH_PATTERN = Regex("^\\+\\+\\+ b/(.+)$", RegexOption.MULTILINE)
  }
}
