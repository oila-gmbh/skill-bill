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
import skillbill.cli.core.ExternalCommand
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
class UpdateCommand(
  private val updateCheckService: UpdateCheckService,
  private val state: CliRunState,
) : DocumentedCliCommand("update", "Update Skill Bill by running the official installer.") {
  private val release by option("--release", help = "Install a specific release tag instead of latest stable.")
  private val withDesktopApp by option("--with-desktop-app", help = "Install the optional desktop app.")
    .flag(default = false)
  private val noDesktopApp by option("--no-desktop-app", help = "Skip desktop app installation.")
    .flag(default = false)
  private val desktopAppDir by option("--desktop-app-dir", help = "Override the desktop app install directory.")
  private val preferUpstream by option(
    "--prefer-upstream",
    help = "Overwrite local skill conflicts with the upstream version.",
  ).flag(default = false)
  private val clean by option(
    "--clean",
    help = "Wipe installed skills, platform packs, and orchestration before staging the candidate tree.",
  ).flag(default = false)
  private val dryRun by option("--dry-run", help = "Print the installer command without running it.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    require(!(withDesktopApp && noDesktopApp)) {
      "--with-desktop-app and --no-desktop-app cannot be used together."
    }
    val plan = updatePlan()
    if (dryRun) {
      state.complete(plan.toPayload("dry_run"), format)
      return
    }
    if (release == null) {
      val updateCheck = updateCheckService.check(includePrereleases = false)
      if (updateCheck.status != UpdateCheckStatus.UPDATE_AVAILABLE) {
        completeSkippedUpdate(updateCheck, plan)
        return
      }
    }
    val result = runInstaller(plan.command)
    val payload = plan.toPayload(if (result.exitCode == 0) "completed" else "failed") +
      ("exit_code" to result.exitCode) +
      ("installer_output" to result.output)
    if (format.wireName == "json") {
      state.complete(payload, format, exitCode = result.exitCode)
    } else {
      state.completeText(result.output, payload, exitCode = result.exitCode)
    }
  }

  private fun updatePlan(): UpdateCommandPlan {
    val installerArgs = buildList {
      add("--reuse-last-selection")
      release?.let {
        add("--release")
        add(it)
      }
      if (withDesktopApp) add("--with-desktop-app")
      if (noDesktopApp) add("--no-desktop-app")
      desktopAppDir?.let {
        add("--desktop-app-dir")
        add(it)
      }
      if (preferUpstream) add("--prefer-upstream")
      if (clean) add("--clean")
    }
    val command = buildString {
      append("curl -fsSL ")
      append(INSTALL_SCRIPT_URL)
      append(" | bash -s --")
      installerArgs.forEach { arg ->
        append(' ')
        append(shellQuote(arg))
      }
    }
    return UpdateCommandPlan(command = command, installerArgs = installerArgs)
  }

  private fun runInstaller(command: String): InstallerRunResult {
    val environment = state.environment.toMutableMap().apply {
      put("HOME", state.userHome.toString())
    }
    val result = state.externalCommandRunner.run(
      ExternalCommand(
        executable = "bash",
        arguments = listOf("-c", command),
        environment = environment,
      ),
    )
    return InstallerRunResult(exitCode = result.exitCode, output = result.output)
  }

  private fun completeSkippedUpdate(updateCheck: UpdateCheckResult, plan: UpdateCommandPlan) {
    val exitCode = if (updateCheck.status == UpdateCheckStatus.UNKNOWN) 1 else 0
    val status = if (updateCheck.status == UpdateCheckStatus.UNKNOWN) "check_failed" else "skipped"
    val payload = plan.toPayload(status) +
      ("update_check" to updateCheck.toPayload()) +
      ("reason" to updateSkipReason(updateCheck))
    if (format.wireName == "json") {
      state.complete(payload, format, exitCode = exitCode)
    } else {
      state.completeText(updateSkipText(updateCheck), payload, exitCode = exitCode)
    }
  }

  private fun UpdateCommandPlan.toPayload(status: String): Map<String, Any?> = linkedMapOf(
    "status" to status,
    "command" to command,
    "installer_args" to installerArgs,
  )

  private data class UpdateCommandPlan(
    val command: String,
    val installerArgs: List<String>,
  )

  private data class InstallerRunResult(
    val exitCode: Int,
    val output: String,
  )
}

private fun updateSkipReason(updateCheck: UpdateCheckResult): String = when (updateCheck.status) {
  UpdateCheckStatus.UP_TO_DATE -> "installed version is already the latest release"
  UpdateCheckStatus.AHEAD_OF_RELEASE -> "installed version is newer than the latest release"
  UpdateCheckStatus.UNKNOWN -> "could not determine the latest release"
  UpdateCheckStatus.UPDATE_AVAILABLE -> "update is available"
}

private fun updateSkipText(updateCheck: UpdateCheckResult): String = buildString {
  append(updateCheck.toText())
  appendLine("update_status: ${if (updateCheck.status == UpdateCheckStatus.UNKNOWN) "check_failed" else "skipped"}")
  appendLine("reason: ${updateSkipReason(updateCheck)}")
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

private const val INSTALL_SCRIPT_URL = "https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh"

private val SHELL_SAFE_PATTERN = Regex("[A-Za-z0-9_./:=@%+-]+")

private fun shellQuote(value: String): String = if (SHELL_SAFE_PATTERN.matches(value)) {
  value
} else {
  "'${value.replace("'", "'\"'\"'")}'"
}
