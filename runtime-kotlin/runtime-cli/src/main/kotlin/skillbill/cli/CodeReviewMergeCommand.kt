package skillbill.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.ParallelCodeReviewMergeService
import skillbill.application.model.ParallelCodeReviewMergeRequest
import skillbill.cli.models.CliExecutionResult
import skillbill.install.model.InstallAgent
import java.io.IOException
import java.nio.file.Path

@Inject
class CodeReviewMergeCommand(
  private val mergeService: ParallelCodeReviewMergeService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "code-review-merge",
  "Merge two parallel review lane outputs into a single risk register with provenance labels.",
) {
  private val lane1Agent by option(
    "--lane1-agent",
    help = "Agent ID for lane 1 (the inline reviewer). Supported: ${InstallAgent.supportedIds.joinToString()}.",
  ).required()
  private val lane1Path by option(
    "--lane1",
    help = "Path to the file containing lane 1 raw review output.",
  ).required()
  private val lane2Agent by option(
    "--lane2-agent",
    help = "Agent ID for lane 2 (the background subprocess reviewer). " +
      "Supported: ${InstallAgent.supportedIds.joinToString()}.",
  ).required()
  private val lane2Path by option(
    "--lane2",
    help = "Path to the file containing lane 2 raw review output.",
  ).required()

  override fun run() {
    val lane1Text = readLane("--lane1", lane1Path)
    val lane2Text = readLane("--lane2", lane2Path)

    val mergeResult = mergeService.merge(
      ParallelCodeReviewMergeRequest(
        lane1AgentId = lane1Agent,
        lane1RawOutput = lane1Text,
        lane2AgentId = lane2Agent,
        lane2RawOutput = lane2Text,
      ),
    )
    state.result = CliExecutionResult(exitCode = 0, stdout = mergeResult.formattedOutput)
  }

  private fun readLane(option: String, path: String): String = try {
    Path.of(path).toFile().readText()
  } catch (@Suppress("SwallowedException") e: IOException) {
    throw UsageError("Cannot read $option file '$path': ${e.message}")
  }
}
