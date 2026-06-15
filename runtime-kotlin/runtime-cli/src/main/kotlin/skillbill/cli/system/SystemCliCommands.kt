package skillbill.cli.system

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.model.UpdateCheckResult
import skillbill.application.model.UpdateCheckStatus
import skillbill.application.system.SystemService
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.learning.toPayload
import skillbill.cli.model.CliExecutionResult

@Inject
class VersionCommand(
  private val service: SystemService,
  private val state: CliRunState,
) : DocumentedCliCommand("version", "Show the installed skill-bill version.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.version().toPayload(), format)
  }
}

@Inject
class UpdateCheckCommand(
  private val service: UpdateCheckService,
  private val state: CliRunState,
) : DocumentedCliCommand("update-check", "Check whether a Skill Bill update is available.") {
  private val includePrereleases by option(
    "--include-prereleases",
    help = "Include prerelease GitHub releases in the comparison.",
  ).flag(default = false)
  private val format by formatOption()

  override fun run() {
    val result = service.check(includePrereleases)
    if (format.wireName == "json") {
      state.complete(result.toPayload(), format, exitCode = 0)
    } else {
      state.completeText(result.toText(), result.toPayload(), exitCode = 0)
    }
  }
}

@Inject
class DoctorCliCommand(
  private val service: SystemService,
  private val state: CliRunState,
) : DocumentedCliCommand("doctor", "Check skill-bill installation health.") {
  private val subject by argument(help = "Optional diagnostic subject. Use `skill` for one governed skill.")
    .optional()
  private val skillName by argument(help = "Governed skill name when diagnosing one skill.").optional()
  private val repoRoot by option("--repo-root", help = "Repo root to inspect when using `doctor skill`.").default(".")
  private val content by option("--content", help = "How much content.md text to include when using `doctor skill`.")
    .choice("none", "preview", "full")
    .default("preview")
  private val format by formatOption()

  override fun run() {
    if (subject == null) {
      state.complete(service.doctor(state.dbOverride).toPayload(), format)
    } else {
      state.result = retiredSubjectResult(subject.orEmpty(), skillName.orEmpty(), repoRoot, content)
    }
  }
}

private fun retiredSubjectResult(
  subject: String,
  skillName: String,
  repoRoot: String,
  content: String,
): CliExecutionResult {
  val replacementSkillName = skillName.ifBlank { "<skill-name>" }
  val replacement = when (subject) {
    "skill" -> "skill-bill show $replacementSkillName --repo-root $repoRoot --content $content"
    else -> "skill-bill doctor"
  }
  val message = when (subject) {
    "skill" -> "doctor skill was retired in SKILL-32; use `$replacement` instead."
    else -> "doctor subject '$subject' is unsupported; use `$replacement` instead."
  }
  return CliExecutionResult(exitCode = 1, stdout = message)
}

private fun UpdateCheckResult.toText(): String = buildString {
  when (status) {
    UpdateCheckStatus.UP_TO_DATE -> {
      appendLine("status: up_to_date")
      appendLine("installed_version: $installedVersion")
      appendLine("latest_version: $latestVersion")
    }
    UpdateCheckStatus.UPDATE_AVAILABLE -> {
      appendLine("status: update_available")
      appendLine("installed_version: $installedVersion")
      appendLine("latest_version: $latestVersion")
      appendLine("release_url: $releaseUrl")
      appendLine("recommended_install_command: $recommendedInstallCommand")
    }
    UpdateCheckStatus.AHEAD_OF_RELEASE -> {
      appendLine("status: ahead_of_release")
      appendLine("installed_version: $installedVersion")
      appendLine("latest_version: $latestVersion")
      appendLine("release_url: $releaseUrl")
    }
    UpdateCheckStatus.UNKNOWN -> {
      appendLine("status: unknown")
      appendLine("reason: ${reason.orEmpty()}")
      installedVersion?.let { appendLine("installed_version: $it") }
    }
  }
}

private fun UpdateCheckResult.toPayload(): Map<String, Any?> = linkedMapOf(
  "status" to status.wireName,
  "installed_version" to installedVersion,
  "latest_version" to latestVersion,
  "release_url" to releaseUrl,
  "recommended_install_command" to recommendedInstallCommand,
  "reason" to reason,
)
