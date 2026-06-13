package skillbill.cli.repovalidation

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.RepoValidationService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.learning.toPayload
import skillbill.cli.model.CliFormat
import java.nio.file.Path

@Inject
class RepoValidationCliCommands(
  private val state: CliRunState,
  private val repoValidationService: RepoValidationService,
) {
  val commands = listOf(
    ValidateAgentConfigsCommand(state, repoValidationService),
    ValidateReleaseRefCommand(state, repoValidationService),
  )
}

class ValidateAgentConfigsCommand(
  private val state: CliRunState,
  private val repoValidationService: RepoValidationService,
) : DocumentedCliCommand(
  "validate-agent-configs",
  "Validate Skill Bill governed skills, platform packs, add-ons, docs catalog, and workflow contracts.",
) {
  private val repoRoot by option("--repo-root", help = "Repository root to inspect.").default(".")
  private val format by formatOption()

  override fun run() {
    val report = repoValidationService.validateRepo(Path.of(repoRoot))
    val payload = report.toPayload()
    if (format == CliFormat.JSON) {
      state.complete(payload, format, exitCode = if (report.passed) 0 else 1)
      return
    }

    val text = if (report.passed) {
      buildString {
        appendLine("Agent-config validation passed.")
        appendLine(
          "Validated ${report.skillCount} skills, ${report.addonCount} governed add-on files, " +
            "${report.platformPackCount} platform packs, ${report.nativeAgentCount} native agents, README catalog, " +
            "skill references, and workflow contracts.",
        )
      }
    } else {
      buildString {
        appendLine("Agent-config validation failed:")
        report.issues.forEach { issue -> appendLine("- $issue") }
      }
    }
    state.completeText(text, payload, exitCode = if (report.passed) 0 else 1)
  }
}

class ValidateReleaseRefCommand(
  private val state: CliRunState,
  private val repoValidationService: RepoValidationService,
) : DocumentedCliCommand(
  "validate-release-ref",
  "Validate a release tag and emit release metadata.",
) {
  private val ref by argument(help = "Tag or refs/tags/... reference to validate.").optional()
  private val githubOutput by option(
    "--github-output",
    help = "Optional file path where GitHub Actions step outputs should be appended.",
  )
  private val format by formatOption()

  override fun run() {
    val rawRef = ref
      ?: state.environment["GITHUB_REF_NAME"]
      ?: state.environment["GITHUB_REF"]
    if (rawRef == null) {
      state.completeText(
        "No release ref supplied. Pass a tag or set GITHUB_REF_NAME.\n",
        mapOf("status" to "failed", "error" to "No release ref supplied. Pass a tag or set GITHUB_REF_NAME."),
        exitCode = 1,
      )
      return
    }

    val metadata = try {
      repoValidationService.parseReleaseRef(rawRef)
    } catch (error: IllegalArgumentException) {
      state.completeText(
        "${error.message}\n",
        mapOf("status" to "failed", "error" to error.message.orEmpty()),
        exitCode = 1,
      )
      return
    }

    githubOutput?.let { outputPath ->
      repoValidationService.appendGithubOutput(Path.of(outputPath), metadata)
    }
    state.complete(metadata.toPayload(), format)
  }
}
