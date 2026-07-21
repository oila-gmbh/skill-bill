package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.application.review.model.DelegatedReviewExecutionOutcome
import skillbill.application.review.model.DelegatedReviewExecutionRequest
import skillbill.application.review.model.DelegatedReviewLaunchRequest
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.workflow.repoRoot
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTokenOwnership
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.ParallelReviewMerger
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.TokenOwnership
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Inject
@Suppress("LongParameterList")
class ParallelCodeReviewRunner(
  private val delegatedReviewExecutionBroker: DelegatedReviewExecutionBroker,
  private val scaffoldCatalogService: ScaffoldCatalogService,
  private val diffResolver: DiffResolverPort,
  private val parallelLaneRunner: ParallelReviewLaneRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewRubricResolver: ReviewRubricResolver,
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
    val manifest = detectStack(diffText, request.repoRoot)
    val prepared = prepare(request, diffText, manifest, listOf(agent1.id, agent2.id))

    val timeoutSec = request.timeout?.inWholeSeconds ?: (DEFAULT_TIMEOUT_MINUTES * SECONDS_PER_MINUTE)
    val laneRunResult = parallelLaneRunner.runTwoLanes(
      ParallelReviewLaneRunRequest(
        lane1 = { launchLane(prepared[0], request) },
        lane2 = { launchLane(prepared[1], request, request.agent2Model) },
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
      lane1 = ParallelReviewLaneStatus(
        agent1.id,
        outcome1.success,
        outcome1.failureReason,
        outcome1.tokenUsage,
        outcome1.budgetOutcome,
        outcome1.accounting,
      ),
      lane2 = ParallelReviewLaneStatus(
        agent2.id,
        outcome2.success,
        outcome2.failureReason,
        outcome2.tokenUsage,
        outcome2.budgetOutcome,
        outcome2.accounting,
      ),
    )
  }

  private fun prepare(
    request: ParallelCodeReviewRequest,
    diffText: String,
    manifest: PlatformManifest?,
    agentIds: List<String>,
  ): List<DelegatedReviewLaunchRequest> {
    val resolvedRubric = reviewRubricResolver.resolve(manifest)
    val budget = repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(request.repoRoot))
      .config.reviewContextBudget
    return ParallelReviewPreparationCompiler.compile(
      input = ParallelReviewPreparationInput(
        diff = diffText,
        stack = manifest?.slug,
        agents = agentIds,
        repoRoot = request.repoRoot,
        rubric = ReviewRubricProjection(resolvedRubric.rubricId, resolvedRubric.body),
      ),
      budget = budget,
      envelopeValidator = reviewContextEnvelopeValidator,
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

  private fun detectStack(diffText: String, repoRoot: Path): PlatformManifest? {
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
    return if (best != null && best.value > 0) best.key else null
  }

  private fun launchLane(
    launchRequest: DelegatedReviewLaunchRequest,
    request: ParallelCodeReviewRequest,
    modelOverride: String? = null,
  ): ParallelReviewLaneOutcome {
    val execution = delegatedReviewExecutionBroker.execute(
      DelegatedReviewExecutionRequest(
        launchRequest = launchRequest,
        repoRoot = request.repoRoot,
        timeout = request.timeout ?: DEFAULT_TIMEOUT_MINUTES.minutes,
        modelOverride = modelOverride,
      ),
    )
    return when (execution) {
      is DelegatedReviewExecutionOutcome.Terminated -> ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason = describeBudgetOutcome(execution.budgetOutcome),
        budgetOutcome = execution.budgetOutcome,
      )
      is DelegatedReviewExecutionOutcome.Completed -> {
        val worker = execution.worker
        worker.budgetOutcome?.takeIf { worker.facts == null }?.let { budgetOutcome ->
          return ParallelReviewLaneOutcome(
            success = false,
            rawOutput = "",
            failureReason = describeBudgetOutcome(budgetOutcome),
            budgetOutcome = budgetOutcome,
            accounting = worker.accounting,
          )
        }
        worker.forbiddenOperation?.let { forbidden ->
          return ParallelReviewLaneOutcome(
            success = false,
            rawOutput = "",
            failureReason = "forbidden review operation: ${forbidden.reason}",
            accounting = worker.accounting,
          )
        }
        val outcome = worker.facts
        if (outcome == null) {
          return ParallelReviewLaneOutcome(
            success = false,
            rawOutput = "",
            failureReason = "unsupported agent: ${worker.unsupportedReason}",
          )
        }
        val usage = providerTokenUsage(outcome)
        val processFailure = laneFailureReason(outcome)
        val budgetOutcome = worker.budgetOutcome
        val reason = budgetOutcome?.let(::describeBudgetOutcome) ?: processFailure
        ParallelReviewLaneOutcome(
          success = reason == null,
          rawOutput = outcome.stdout,
          failureReason = reason,
          tokenUsage = usage,
          budgetOutcome = budgetOutcome,
          accounting = worker.accounting,
        )
      }
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

private fun describeBudgetOutcome(outcome: ReviewBudgetOutcome): String =
  "${outcome.type}: ${outcome.budgetKind} ${outcome.observedValue} > ${outcome.configuredLimit}"

private fun providerTokenUsage(outcome: AgentRunLaunchFacts): ProviderTokenUsage? {
  val values = listOf(
    outcome.inputTokens,
    outcome.cachedInputTokens,
    outcome.outputTokens,
    outcome.reasoningTokens,
    outcome.totalTokens,
  )
  if (values.none { it != null }) return null
  return ProviderTokenUsage(
    inputTokens = outcome.inputTokens,
    cachedInputTokens = outcome.cachedInputTokens,
    outputTokens = outcome.outputTokens,
    reasoningTokens = outcome.reasoningTokens,
    totalTokens = outcome.totalTokens,
    ownership = if (outcome.tokenOwnership == AgentRunTokenOwnership.INCLUSIVE) {
      TokenOwnership.INCLUSIVE
    } else {
      TokenOwnership.DIRECT
    },
  )
}
