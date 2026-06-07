package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.ReviewRubricPort
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.model.ParallelReviewLaneResult
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@Inject
class ParallelCodeReviewRunner(
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val diffResolver: DiffResolverPort,
  private val rubricPort: ReviewRubricPort,
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
    val prompt = buildPrompt(stack, diffText)

    val timeoutSec = request.timeout?.inWholeSeconds ?: (DEFAULT_TIMEOUT_MINUTES * SECONDS_PER_MINUTE)
    val executor = Executors.newFixedThreadPool(2)
    var outcome1 = LaneOutcome(success = false, rawOutput = "", failureReason = "interrupted while waiting for lanes")
    var outcome2 = LaneOutcome(success = false, rawOutput = "", failureReason = "interrupted while waiting for lanes")
    try {
      // invokeAll gives both lanes the full shared budget and avoids the sequential-await timing
      // problem where lane 2 inherits only the leftover time after lane 1 finishes.
      val futures = executor.invokeAll(
        listOf(
          Callable { launchLane(agent1.id, prompt, request) },
          Callable { launchLane(agent2.id, prompt, request, request.agent2Model) },
        ),
        timeoutSec + TIMEOUT_BUFFER_SECONDS,
        TimeUnit.SECONDS,
      )
      outcome1 = resultFromFuture(futures[0])
      outcome2 = resultFromFuture(futures[1])
    } catch (@Suppress("SwallowedException") e: InterruptedException) {
      Thread.currentThread().interrupt()
    } finally {
      executor.shutdownNow()
      try {
        executor.awaitTermination(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      } catch (@Suppress("SwallowedException") _: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }

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
      lane1 = ParallelReviewLaneStatus(agent1.id, outcome1.success, outcome1.failureReason),
      lane2 = ParallelReviewLaneStatus(agent2.id, outcome2.success, outcome2.failureReason),
    )
  }

  // Collects one lane's result from a future that invokeAll has already settled. No blocking occurs
  // here; the only exception paths are CancellationException (shared budget exhausted) and
  // ExecutionException (unexpected throw inside the lane callable).
  private fun resultFromFuture(future: Future<LaneOutcome>): LaneOutcome = try {
    future.get()
  } catch (@Suppress("SwallowedException") e: CancellationException) {
    LaneOutcome(success = false, rawOutput = "", failureReason = "lane timed out (cancelled by shared budget)")
  } catch (e: ExecutionException) {
    val cause = e.cause ?: e
    LaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "lane launch threw ${cause::class.simpleName}: ${cause.message ?: "no detail"}",
    )
  } catch (@Suppress("SwallowedException") e: InterruptedException) {
    Thread.currentThread().interrupt()
    LaneOutcome(success = false, rawOutput = "", failureReason = "interrupted while collecting lane result")
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
    val diffText = when (request.scope) {
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
      .toList()

    val scores = manifests.associateWith { manifest ->
      manifest.routingSignals.strong.sumOf { signal ->
        diffPaths.count { path -> path.contains(signal, ignoreCase = true) }
      }
    }

    val best = scores.maxByOrNull { it.value }
    return if (best != null && best.value > 0) best.key.slug else null
  }

  private fun buildPrompt(stack: String?, diffText: String): String = buildString {
    appendLine(
      "You are driving one lane of a parallel code review. Apply all of the following specialist " +
        "review rubrics to the diff below and return a single consolidated Risk Register. " +
        "Do not use parallel mode.",
    )
    appendLine()
    val rubrics = if (stack != null) rubricPort.loadSpecialistRubrics(stack) else emptyList()
    if (rubrics.isNotEmpty()) {
      rubrics.forEach { rubric ->
        appendLine("## Specialist: ${rubric.skillName}")
        appendLine(rubric.content)
        appendLine()
      }
    } else {
      if (stack != null) appendLine("The dominant stack is $stack.")
      appendLine("Review for architecture, correctness, testing, performance, and reliability risks.")
      appendLine()
    }
    appendLine(
      "Return only a risk register in F-XXX bullet format, one finding per line: " +
        "- [F-NNN] Blocker|Major|Minor|Nit | High|Medium|Low | file:line | description",
    )
    appendLine()
    appendLine("Diff:")
    append(diffText)
  }

  private fun launchLane(
    agentId: String,
    prompt: String,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
  ): LaneOutcome {
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
        ),
      ),
    )
    return when (outcome) {
      is AgentRunLaunchFacts -> {
        val reason = laneFailureReason(outcome)
        LaneOutcome(success = reason == null, rawOutput = outcome.stdout, failureReason = reason)
      }
      is UnsupportedAgentRunLaunch ->
        LaneOutcome(success = false, rawOutput = "", failureReason = "unsupported agent: ${outcome.reason}")
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

  private data class LaneOutcome(val success: Boolean, val rawOutput: String, val failureReason: String? = null)

  private companion object {
    const val DEFAULT_TIMEOUT_MINUTES = 30L
    const val TIMEOUT_BUFFER_SECONDS = 30L
    const val SECONDS_PER_MINUTE = 60L
    const val DESTROY_WAIT_TIMEOUT_MILLIS = 5_000L
    const val STDERR_EXCERPT_MAX_LENGTH = 120
    val DIFF_PATH_PATTERN = Regex("^\\+\\+\\+ b/(.+)$", RegexOption.MULTILINE)
  }
}
