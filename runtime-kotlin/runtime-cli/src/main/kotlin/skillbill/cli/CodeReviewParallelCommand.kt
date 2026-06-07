package skillbill.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import me.tatarka.inject.annotations.Inject
import skillbill.application.ParallelCodeReviewRunner
import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.UsageValidationException
import skillbill.cli.models.CliExecutionResult
import skillbill.install.model.InstallAgent
import skillbill.install.model.InvokingAgentContextResolver
import java.nio.file.Path

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
      runner.run(
        ParallelCodeReviewRequest(
          agent1Id = resolvedAgent1,
          agent2Id = resolvedAgent2,
          agent2Model = model2?.takeIf(String::isNotBlank),
          scope = resolvedScope,
          repoRoot = Path.of(repoRoot).toAbsolutePath().normalize(),
          timeoutMinutes = timeoutMinutes,
        ),
      )
    } catch (@Suppress("SwallowedException") e: UsageValidationException) {
      throw UsageError(e.message.orEmpty())
    } catch (@Suppress("SwallowedException") e: DiffResolutionException) {
      throw UsageError(e.message.orEmpty())
    }

    val output = result.mergeResult.formattedOutput
    val exitCode = if (result.lane1Success && result.lane2Success) 0 else 1
    state.result = CliExecutionResult(exitCode = exitCode, stdout = output)
  }

  private fun resolveAgent1(): String = agent1?.takeIf(String::isNotBlank)
    ?: state.environment["SKILL_BILL_AGENT"]?.takeIf(String::isNotBlank)
    ?: InvokingAgentContextResolver.detect(state.environment)?.id
    ?: DEFAULT_AGENT

  private companion object {
    const val DEFAULT_AGENT = "codex"
  }
}
