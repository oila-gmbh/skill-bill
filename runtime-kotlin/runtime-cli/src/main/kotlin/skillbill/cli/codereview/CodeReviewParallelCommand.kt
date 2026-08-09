package skillbill.cli.codereview

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import me.tatarka.inject.annotations.Inject
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
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
import skillbill.cli.model.CliExecutionResult
import skillbill.contracts.JsonSupport
import skillbill.error.ReviewAggregationIntegrityError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InvokingAgentContextResolver
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class CodeReviewParallelCommand(
  private val runner: ParallelCodeReviewRunner,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "code-review-parallel",
  "Run two review agents in parallel on the same diff and merge findings.",
) {
  private val agent1 by option(
    "--agent1",
    help = "Agent for the default lane. Resolution order: --agent1, then SKILL_BILL_AGENT, " +
      "then the detected invoking-agent context, then a documented last-resort default ($DEFAULT_AGENT).",
  )
  private val agent2 by option(
    "--agent2",
    help = "Agent for the alternative lane. Required. Supported: ${InstallAgent.supportedIds.joinToString()}.",
  )
  private val model2 by option(
    "--model2",
    help = "Model override for the alternative lane agent (e.g. claude-opus-4-8, o3). Optional.",
  )
  private val scope by option(
    "--scope",
    help = "Diff scope: staged, unstaged, branch (default), or pr.",
  ).choice("staged", "unstaged", "branch", "pr").default("branch")
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
    help = "Shared execution mode for both lanes: inline (default, one review prompt per lane), " +
      "auto (also resolves inline), or delegated (experimental specialist fan-out).",
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
    val resolvedAgent2 = agent2?.takeIf(String::isNotBlank)
      ?: throw UsageError(
        "Option --agent2 is required. Supported agents: ${InstallAgent.supportedIds.joinToString()}.",
      )

    val resolvedScope = when (scope) {
      "staged" -> ParallelReviewScope.STAGED
      "unstaged" -> ParallelReviewScope.UNSTAGED
      "branch" -> ParallelReviewScope.BRANCH
      "pr" -> ParallelReviewScope.PR
      else -> throw UsageError("Invalid scope: $scope")
    }

    val result = try {
      runner.run(request(resolvedAgent1, resolvedAgent2, resolvedScope))
    } catch (@Suppress("SwallowedException") e: UsageValidationException) {
      throw UsageError(e.message.orEmpty())
    } catch (@Suppress("SwallowedException") e: DiffResolutionException) {
      throw UsageError(e.message.orEmpty())
    } catch (@Suppress("SwallowedException") e: StackDetectionException) {
      throw UsageError(e.message.orEmpty())
    } catch (e: ReviewAggregationIntegrityError) {
      // An aggregation breach is a coverage report, not a crash: the reader has to be told which
      // lanes could not be merged rather than seeing an uncaught exception and no register at all.
      state.result = CliExecutionResult(exitCode = 1, stdout = e.message.orEmpty())
      return
    }

    val lanes = listOf(result.lane1, result.lane2)
    val exitCode = if (lanes.all(ParallelReviewLaneStatus::success)) 0 else 1
    val output = buildString {
      append(laneStatusOutput(lanes, result.mergeResult.formattedOutput))
      // Coverage honesty is part of the report, not an internal field: a reader has to be told when
      // a lane left units unreviewed, and when commit-focused sequencing did not apply at all.
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

  private fun request(resolvedAgent1: String, resolvedAgent2: String, resolvedScope: ParallelReviewScope) =
    ParallelCodeReviewRequest(
      agent1Id = resolvedAgent1,
      agent2Id = resolvedAgent2,
      agent2Model = model2?.takeIf(String::isNotBlank),
      scope = resolvedScope,
      repoRoot = Path.of(repoRoot).toAbsolutePath().normalize(),
      timeout = timeoutMinutes?.minutes,
      codeReviewMode = parseExecutionMode(codeReviewMode),
      suppliedDiffPath = suppliedDiffPath(),
      reviewRunId = reviewRunId?.takeIf(String::isNotBlank),
      baseRevision = baseRevision?.takeIf(String::isNotBlank),
      headRevision = headRevision?.takeIf(String::isNotBlank),
      prelaunchExpansions = expandFiles.map(::parseExpansion),
      baselineUntrackedPolicy = ParallelCodeReviewRequest.baselineUntrackedPolicy(
        baselineUntrackedIncludes,
        baselineUntrackedExcludes,
      ),
    )

  // On any lane failure, prepend a single status line naming which lane failed and why. The line is
  // not in `- [F-NNN]` form, so the finding parser ignores it; the merged register stays intact.
  private fun laneStatusOutput(lanes: List<ParallelReviewLaneStatus>, register: String): String {
    if (lanes.all(ParallelReviewLaneStatus::success)) return register
    val summary = lanes.joinToString(" | ") { lane ->
      if (lane.success) "${lane.agentId}: ok" else "${lane.agentId}: failed (${lane.failureReason ?: "unknown reason"})"
    }
    return "# Lane status — $summary\n$register"
  }

  private fun resolveAgent1(): String = agent1?.takeIf(String::isNotBlank)
    ?: state.environment["SKILL_BILL_AGENT"]?.takeIf(String::isNotBlank)
    ?: InvokingAgentContextResolver.detect(state.environment)?.id
    ?: DEFAULT_AGENT

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

  private companion object {
    const val DEFAULT_AGENT = "codex"
  }
}
