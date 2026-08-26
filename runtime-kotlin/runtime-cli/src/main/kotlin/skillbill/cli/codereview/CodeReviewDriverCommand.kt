package skillbill.cli.codereview

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import skillbill.application.config.ConfigResolutionService
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelCodeReviewResult
import skillbill.application.model.ParallelReviewLaneStatus
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.ReviewPrelaunchExpansion
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.application.review.RequestedReviewMode
import skillbill.application.review.toBoundedPayload
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.invokingAgentResolutionHelp
import skillbill.cli.core.requireInvokingAgentId
import skillbill.cli.model.CliExecutionResult
import skillbill.contracts.JsonSupport
import skillbill.error.ReviewAggregationIntegrityError
import skillbill.error.ShellContentContractException
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

open class CodeReviewDriverCommand(
  name: String,
  help: String,
  private val runner: ParallelCodeReviewRunner,
  private val state: CliRunState,
  @Suppress("UnusedPrivateProperty")
  private val configResolutionService: ConfigResolutionService,
) : DocumentedCliCommand(name, help) {
  protected open val commitTarget: String? = null
  private val agent1 by option(
    "--agent1",
    help = "Agent for the default lane. " + invokingAgentResolutionHelp("--agent1"),
  )
  private val scope by option(
    "--scope",
    help = "Diff scope: staged, unstaged, branch (default), or pr.",
  ).choice("staged", "unstaged", "branch", "pr").default(DEFAULT_CODE_REVIEW_SCOPE)
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root for diff and agent runs.",
  ).default(".")
  private val timeoutMinutes by option(
    "--timeout-minutes",
    help = "Optional per-lane wall-clock cap in minutes.",
  ).long()
  private val diffFile by option(
    "--diff-file",
    help = "Exact diff input for both lanes. When supplied, it replaces the configured review scope.",
  )
  private val baseRevision by option(
    "--base-revision",
    help = "Immutable base identity for --diff-file. Must be paired with --head-revision.",
  )
  private val headRevision by option(
    "--head-revision",
    help = "Immutable head identity for --diff-file. Must be paired with --base-revision.",
  )
  private val expandFiles by option(
    "--expand-file",
    help = "Governed prelaunch whole-file evidence as LANE:PATH=REACHABILITY_REASON. Repeatable.",
  ).multiple()
  private val codeReviewMode by option(
    "--execution-mode",
    help = "Execution mode: inline (default, one review prompt), auto (resolves inline), " +
      "or delegated (parent launches specialists; parent authors the final prose result).",
  ).default(CodeReviewExecutionMode.DEFAULT.wireValue)
  private val baselineUntrackedIncludes by option(
    "--baseline-untracked-include",
    help = "Baseline-untracked path to include in the packet. Repeatable.",
  ).multiple()
  private val baselineUntrackedExcludes by option(
    "--baseline-untracked-exclude",
    help = "Baseline-untracked path to exclude from the packet. Repeatable.",
  ).multiple()
  private val reviewRunId by option(
    "--review-run-id",
    help = "Review run id (rvw-YYYYMMDD-HHMMSS-XXXX) this review will report. Pass the same id used " +
      "in the review output and import so review accounting is reachable from review_finished telemetry.",
  )

  override fun run() {
    val resolvedAgent1 = resolveAgent1()
    val repo = Path.of(repoRoot).toAbsolutePath().normalize()
    validateCommitTarget()
    val result = runParallelReviewDriver(
      runner,
      request(resolvedAgent1, parsedReviewScope(scope), repo),
      state,
    ) ?: return
    writeParallelReviewResult(state, result)
  }

  private fun request(
    resolvedAgent1: String,
    resolvedScope: ParallelReviewScope,
    repo: Path,
  ): ParallelCodeReviewRequest {
    val (resolvedBase, resolvedHead) = resolveCodeReviewRevisions(commitTarget, baseRevision, headRevision)
    return ParallelCodeReviewRequest(
      agent1Id = resolvedAgent1,
      scope = resolvedScope,
      repoRoot = repo,
      timeout = timeoutMinutes?.minutes,
      codeReviewMode = parseExecutionMode(codeReviewMode),
      suppliedDiffPath = suppliedDiffPath(),
      reviewRunId = reviewRunId?.takeIf(String::isNotBlank),
      baseRevision = resolvedBase,
      headRevision = resolvedHead,
      prelaunchExpansions = expandFiles.map(::parseExpansion),
      baselineUntrackedPolicy = ParallelCodeReviewRequest.baselineUntrackedPolicy(
        baselineUntrackedIncludes,
        baselineUntrackedExcludes,
      ),
    )
  }

  private fun resolveAgent1(): String = requireInvokingAgentId(agent1, state.environment, "--agent1")

  private fun validateCommitTarget() {
    if (commitTarget.isNullOrBlank()) return
    val error = when {
      scope != DEFAULT_CODE_REVIEW_SCOPE ->
        "A commit target cannot be combined with --scope '$scope'; use the default branch scope."
      diffFile != null -> "A commit target cannot be combined with --diff-file."
      !baseRevision.isNullOrBlank() || !headRevision.isNullOrBlank() ->
        "A commit target cannot be combined with --base-revision or --head-revision."
      else -> null
    }
    if (error != null) {
      throw UsageError(error)
    }
  }

  private fun parseExecutionMode(value: String): CodeReviewExecutionMode = RequestedReviewMode.parse(value)

  private fun suppliedDiffPath(): Path? = diffFile?.let { value ->
    Path.of(value).toAbsolutePath().normalize()
  }

  private fun parseExpansion(value: String): ReviewPrelaunchExpansion {
    val laneSeparator = value.indexOf(':')
    val reasonSeparator = value.indexOf('=', startIndex = laneSeparator + 1)
    if (laneSeparator <= 0 || reasonSeparator <= laneSeparator + 1 || reasonSeparator == value.lastIndex) {
      throw UsageError("--expand-file must use LANE:PATH=REACHABILITY_REASON with non-blank values.")
    }
    return ReviewPrelaunchExpansion(
      lane = value.substring(0, laneSeparator),
      path = value.substring(laneSeparator + 1, reasonSeparator),
      reachabilityReason = value.substring(reasonSeparator + 1),
    )
  }
}

internal const val DEFAULT_CODE_REVIEW_SCOPE = "branch"

internal fun resolveCodeReviewRevisions(
  commitTarget: String?,
  baseRevision: String?,
  headRevision: String?,
): Pair<String?, String?> {
  val target = commitTarget?.takeIf(String::isNotBlank)
  if (target != null) return "$target^" to target
  return baseRevision?.takeIf(String::isNotBlank) to headRevision?.takeIf(String::isNotBlank)
}

private fun parsedReviewScope(scope: String): ParallelReviewScope = when (scope) {
  "staged" -> ParallelReviewScope.STAGED
  "unstaged" -> ParallelReviewScope.UNSTAGED
  DEFAULT_CODE_REVIEW_SCOPE -> ParallelReviewScope.BRANCH
  "pr" -> ParallelReviewScope.PR
  else -> throw UsageError("Invalid scope: $scope")
}

private fun runParallelReviewDriver(
  runner: ParallelCodeReviewRunner,
  request: ParallelCodeReviewRequest,
  state: CliRunState,
): ParallelCodeReviewResult? = try {
  runner.run(request)
} catch (error: UsageValidationException) {
  usageError(error)
} catch (error: DiffResolutionException) {
  usageError(error)
} catch (error: StackDetectionException) {
  usageError(error)
} catch (error: ShellContentContractException) {
  usageError(error)
} catch (error: ReviewAggregationIntegrityError) {
  state.result = CliExecutionResult(exitCode = 1, stdout = error.message.orEmpty())
  null
}

private fun usageError(error: Throwable): Nothing {
  throw UsageError(error.message.orEmpty()).also { usage ->
    runCatching { usage.initCause(error) }
  }
}

private fun writeParallelReviewResult(state: CliRunState, result: ParallelCodeReviewResult) {
  val parent = result.lane1
  val exitCode = if (parent.success) 0 else 1
  val output = buildString {
    append(laneStatusOutput(listOf(parent), result.output))
    laneDiagnosticsOutput(listOf(parent))?.let { diagnostics ->
      appendLine()
      append(diagnostics)
    }
    result.coverage?.let { coverage ->
      appendLine()
      append(coverage.render())
    }
    result.accountingSummary?.let { summary ->
      appendLine()
      append("# Review accounting — ")
      append(JsonSupport.mapToJsonString(summary.toBoundedPayload()))
    }
  }
  state.result = CliExecutionResult(exitCode = exitCode, stdout = output)
}

private fun laneStatusOutput(lanes: List<ParallelReviewLaneStatus>, register: String): String {
  if (lanes.all(ParallelReviewLaneStatus::success)) return register
  val summary = lanes.joinToString(" | ") { lane ->
    if (lane.success) "${lane.agentId}: ok" else "${lane.agentId}: failed (${lane.failureReason ?: "unknown reason"})"
  }
  return "# Lane status — $summary\n$register"
}

private fun laneDiagnosticsOutput(lanes: List<ParallelReviewLaneStatus>): String? = lanes
  .mapNotNull { lane -> lane.droppedCandidateDiagnostic?.let { "${lane.agentId}: $it" } }
  .takeIf { it.isNotEmpty() }
  ?.joinToString(" | ", prefix = "# Lane diagnostics — ")
