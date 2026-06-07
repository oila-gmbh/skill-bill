package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.UsageValidationException
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.model.ParallelReviewLaneResult
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@Inject
class ParallelCodeReviewRunner(
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val diffResolver: DiffResolverPort,
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

    val timeoutSec = (request.timeoutMinutes ?: DEFAULT_TIMEOUT_MINUTES) * SECONDS_PER_MINUTE
    val executor = Executors.newFixedThreadPool(2)
    val (outcome1, outcome2) = try {
      val future1 = executor.submit(Callable { launchLane(agent1.id, prompt, request) })
      val future2 = executor.submit(Callable { launchLane(agent2.id, prompt, request) })
      val startMs = System.currentTimeMillis()
      val o1 = future1.get(timeoutSec + TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS)
      val elapsedSec = (System.currentTimeMillis() - startMs) / MILLIS_PER_SECOND
      val remaining = maxOf(1L, timeoutSec - elapsedSec) + TIMEOUT_BUFFER_SECONDS
      val o2 = future2.get(remaining, TimeUnit.SECONDS)
      Pair(o1, o2)
    } finally {
      executor.shutdownNow()
    }

    val lane1Result = ParallelReviewLaneResult(
      agentId = agent1.id,
      findings = if (outcome1.success) ParallelReviewFindingParser.parse(outcome1.rawOutput) else emptyList(),
      rawOutput = outcome1.rawOutput,
    )
    val lane2Result = ParallelReviewLaneResult(
      agentId = agent2.id,
      findings = if (outcome2.success) ParallelReviewFindingParser.parse(outcome2.rawOutput) else emptyList(),
      rawOutput = outcome2.rawOutput,
    )

    val mergeResult = ParallelReviewMerger.merge(lane1Result, lane2Result)
    return ParallelCodeReviewResult(
      mergeResult = mergeResult,
      lane1Success = outcome1.success,
      lane2Success = outcome2.success,
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
    val manifests = try {
      scaffoldCatalogService.discoverPlatformManifests(packsRoot)
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
      emptyList()
    }
    if (manifests.isEmpty()) return null

    val diffPaths = Regex("^\\+\\+\\+ b/(.+)$", RegexOption.MULTILINE)
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

  private fun buildPrompt(stack: String?, diffText: String): String {
    val stackContext = if (stack != null) "You are reviewing $stack code." else "You are reviewing code."
    return buildString {
      appendLine(stackContext)
      appendLine(
        "Produce a risk register in F-XXX bullet format: " +
          "- [F-NNN] Blocker|Major|Minor|Nit | High|Medium|Low | file:line | description",
      )
      appendLine()
      appendLine("Diff:")
      append(diffText)
    }
  }

  private fun launchLane(agentId: String, prompt: String, request: ParallelCodeReviewRequest): LaneOutcome {
    val outcome = subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = agentId,
        configuredAgentOverrideId = null,
        skillRunRequest = SkillRunRequest(
          issueKey = "code-review-parallel",
          repoRoot = request.repoRoot,
          timeout = request.timeoutMinutes?.minutes,
          promptOverride = prompt,
        ),
      ),
    )
    return when (outcome) {
      is AgentRunLaunchFacts -> LaneOutcome(
        success = outcome.exitStatus == 0 && !outcome.timedOut && !outcome.spawnFailed && !outcome.interrupted,
        rawOutput = outcome.stdout,
      )
      else -> LaneOutcome(success = false, rawOutput = "")
    }
  }

  private data class LaneOutcome(val success: Boolean, val rawOutput: String)

  private companion object {
    const val DEFAULT_TIMEOUT_MINUTES = 30L
    const val TIMEOUT_BUFFER_SECONDS = 30L
    const val SECONDS_PER_MINUTE = 60L
    const val MILLIS_PER_SECOND = 1000L
  }
}
