package skillbill.cli.system

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.McpRegistrationService
import skillbill.application.scaffold.NativeAgentInstallService
import skillbill.application.system.UninstallFileSystemService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption
import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import java.nio.file.Path

@Inject
class UninstallCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
  private val nativeAgentInstallService: NativeAgentInstallService,
  private val mcpRegistrationService: McpRegistrationService,
  private val uninstallFileSystem: UninstallFileSystemService,
) : DocumentedCliCommand("uninstall", "Uninstall Skill Bill from local agents and runtime state.") {
  private val yes by option("--yes", "-y", help = "Skip the interactive confirmation prompt.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Show what would be removed without changing files.")
    .flag(default = false)
  private val desktopAppDir by option("--desktop-app-dir", help = "Override the desktop app install directory.")
  private val format by formatOption()

  override fun run() {
    if (state.environment[GOAL_CONTINUATION_ENV] == "1") {
      val message =
        "Refusing to run skill-bill uninstall during skill-bill goal-continuation.\n" +
          "Goal workers must preserve the active workflow store; uninstall after the goal completes."
      state.completeText(
        message,
        mapOf("status" to "error", "error" to message, "exit_code" to GOAL_CONTINUATION_REFUSAL_EXIT_CODE),
        exitCode = GOAL_CONTINUATION_REFUSAL_EXIT_CODE,
      )
      return
    }

    val plan = uninstallPlan()
    if (!dryRun && !yes && !confirmed(plan)) {
      val payload = plan.toPayload(
        status = "aborted",
        removed = emptyList(),
        skipped = emptyList(),
        warnings = emptyList(),
      )
      completeUninstall("uninstall_status: aborted\n", payload, exitCode = 1)
      return
    }

    if (dryRun) {
      completeUninstall(plan.toText("dry_run"), plan.toPayload("dry_run", emptyList(), emptyList(), emptyList()))
      return
    }

    val result = applyUninstall(plan)
    completeUninstall(result.toText(), result.toPayload(), exitCode = if (result.warnings.isEmpty()) 0 else 1)
  }

  private fun uninstallPlan(): UninstallPlan {
    val home = state.userHome
    val stateRoot = home.resolve(".skill-bill")
    val skillNames = installedSkillNames(uninstallFileSystem, stateRoot.resolve("installed-skills"))
    val legacyNames = legacySkillNames(skillNames)
    val claudeTargets = installAgentService.claudeRoots(home, state.environment).flatMap { root ->
      listOf(root.resolve("skills"), root.resolve("commands"))
    }
    val agentTargets = listOf(
      home.resolve(".copilot/skills"),
      home.resolve(".glm/commands"),
      home.resolve(".codex/skills"),
      home.resolve(".agents/skills"),
      home.resolve(".config/opencode/skills"),
      home.resolve(".junie/skills"),
    ) + claudeTargets
    val stateRuntimeRoot = stateRoot.resolve("runtime")
    val binDir = state.environment["SKILL_BILL_BIN_DIR"]?.let(Path::of) ?: home.resolve(".local/bin")
    return UninstallPlan(
      home = home,
      stateRoot = stateRoot,
      skillNames = skillNames,
      legacyNames = legacyNames,
      agentTargets = agentTargets.distinct(),
      nativeSourceRoots = listOf(stateRoot.resolve("platform-packs"), stateRoot.resolve("skills")),
      mcpAgents = listOf("copilot", "claude", "codex", "opencode", "junie", "glm"),
      launchers = listOf(
        LauncherRemoval(binDir.resolve("skill-bill"), stateRuntimeRoot.resolve("runtime-cli/bin/runtime-cli")),
        LauncherRemoval(binDir.resolve("skill-bill-mcp"), stateRuntimeRoot.resolve("runtime-mcp/bin/runtime-mcp")),
      ),
      desktop = desktopPlan(
        home = home,
        binDir = binDir,
        desktopAppDir = desktopAppDir,
        environment = state.environment,
      ),
    )
  }

  private fun applyUninstall(plan: UninstallPlan): UninstallResult {
    val removed = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    plan.agentTargets.forEach { target ->
      runCatching {
        installAgentService.cleanupAgentTarget(
          targetDir = target,
          skillNames = plan.skillNames,
          legacyNames = plan.legacyNames,
          managedInstallMarker = MANAGED_INSTALL_MARKER,
          home = plan.home,
        )
      }.onSuccess { cleanup ->
        removed += cleanup.removed.map(Path::toString)
        skipped += cleanup.skipped.map(Path::toString)
      }.onFailure { error ->
        warnings += "agent cleanup failed for $target: ${error.message.orEmpty()}"
      }
    }

    if (plan.nativeSourceRoots.any(uninstallFileSystem::exists)) {
      val request = NativeAgentLinkRequest(
        platformPacksRoot = plan.stateRoot.resolve("platform-packs"),
        skillsRoot = plan.stateRoot.resolve("skills"),
        home = plan.home,
      )
      NativeAgentLinkProvider.entries.forEach { provider ->
        runCatching { nativeAgentInstallService.unlinkNativeAgents(provider, request) }
          .onSuccess { unlinked -> removed += unlinked.map(Path::toString) }
          .onFailure { error ->
            warnings += "native agent cleanup failed for ${provider.name.lowercase()}: ${error.message.orEmpty()}"
          }
      }
    }

    plan.mcpAgents.forEach { agent ->
      runCatching { mcpRegistrationService.unregisterMcp(agent, plan.home) }
        .onSuccess { mutation ->
          if (mutation.changed) removed += mutation.configPath.toString()
        }
        .onFailure { error ->
          if (error is ClaudeMcpProfileFailure) {
            removed += error.succeeded.filter { it.changed }.map { it.configPath.toString() }
          }
          warnings += "MCP cleanup failed for $agent: ${error.message.orEmpty()}"
        }
    }

    plan.launchers.forEach { launcher ->
      removeLauncher(uninstallFileSystem, launcher, removed, skipped, warnings)
    }
    removeDesktop(uninstallFileSystem, plan.desktop, removed, skipped, warnings)
    removeRecursively(uninstallFileSystem, plan.stateRoot, removed, warnings)

    return UninstallResult(
      status = if (warnings.isEmpty()) "completed" else "completed_with_warnings",
      removed = removed,
      skipped = skipped,
      warnings = warnings,
    )
  }

  private fun confirmed(plan: UninstallPlan): Boolean {
    state.liveStdout(plan.confirmationText())
    val answer = state.readInputLine()?.trim().orEmpty()
    return answer.equals("y", ignoreCase = true) || answer.equals("yes", ignoreCase = true)
  }

  private fun completeUninstall(text: String, payload: Map<String, Any?>, exitCode: Int = 0) {
    if (format.wireName == "json") {
      state.complete(payload, format, exitCode)
    } else {
      state.completeText(text, payload, exitCode)
    }
  }
}

private const val GOAL_CONTINUATION_ENV = "SKILL_BILL_GOAL_CONTINUATION"
private const val GOAL_CONTINUATION_REFUSAL_EXIT_CODE = 64
private const val MANAGED_INSTALL_MARKER = "Managed by skill-bill install.sh"
private val STAGED_SKILL_DIRECTORY = Regex("""^(.+)-[0-9a-f]{16}$""")

private val RENAMED_SKILL_PAIRS = listOf(
  "bill-code-review-composer" to "bill-code-review-parallel",
  "bill-kotlin-code-review-correctness" to "bill-kotlin-code-review-platform-correctness",
  "bill-kmp-code-review-correctness" to "bill-kmp-code-review-platform-correctness",
)

private fun installedSkillNames(fileSystem: UninstallFileSystemService, installedSkillsRoot: Path): List<String> {
  val names = mutableSetOf<String>()
  fileSystem.listImmediateDirectoryNames(installedSkillsRoot).forEach { name ->
    val match = STAGED_SKILL_DIRECTORY.matchEntire(name)
    if (match != null && !name.startsWith("native-agents-")) {
      names += match.groupValues[1]
    }
  }
  return names.sorted()
}

private fun legacySkillNames(skillNames: List<String>): List<String> {
  val names = mutableSetOf(".bill-shared")
  skillNames.filter { it.startsWith("bill-") }.forEach { skill ->
    names += "mdp-${skill.removePrefix("bill-")}"
  }
  RENAMED_SKILL_PAIRS.forEach { (oldName, newName) ->
    names += oldName
    names += "mdp-${oldName.removePrefix("bill-")}"
    names += "mdp-${newName.removePrefix("bill-")}"
  }
  return names.sorted()
}

private fun removeLauncher(
  fileSystem: UninstallFileSystemService,
  launcher: LauncherRemoval,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  warnings: MutableList<String>,
) {
  if (!fileSystem.exists(launcher.path) && !fileSystem.isSymbolicLink(launcher.path)) {
    return
  }
  if (!fileSystem.isSymbolicLink(launcher.path)) {
    skipped += "${launcher.path} (not a symlink)"
    return
  }
  val target = runCatching { fileSystem.readSymbolicLink(launcher.path) }.getOrElse { error ->
    warnings += "could not read launcher ${launcher.path}: ${error.message.orEmpty()}"
    return
  }
  if (target != launcher.expectedTarget) {
    skipped += "${launcher.path} (points to $target)"
    return
  }
  runCatching { fileSystem.deleteIfExists(launcher.path) }
    .onSuccess { removed += launcher.path.toString() }
    .onFailure { error -> warnings += "could not remove launcher ${launcher.path}: ${error.message.orEmpty()}" }
}

private fun removeDesktop(
  fileSystem: UninstallFileSystemService,
  desktop: DesktopRemoval,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  warnings: MutableList<String>,
) {
  desktop.launcher?.let { removeLauncher(fileSystem, it, removed, skipped, warnings) }
  desktop.files.forEach { file ->
    if (!fileSystem.exists(file)) return@forEach
    runCatching { fileSystem.deleteIfExists(file) }
      .onSuccess { removed += file.toString() }
      .onFailure { error -> warnings += "could not remove $file: ${error.message.orEmpty()}" }
  }
  desktop.directories.forEach { directory -> removeRecursively(fileSystem, directory, removed, warnings) }
}

private fun removeRecursively(
  fileSystem: UninstallFileSystemService,
  path: Path,
  removed: MutableList<String>,
  warnings: MutableList<String>,
) {
  if (!fileSystem.exists(path) && !fileSystem.isSymbolicLink(path)) {
    return
  }
  runCatching { fileSystem.removeTree(path) }
    .onSuccess { entries -> entries.forEach { entry -> removed += entry.toString() } }
    .onFailure { error -> warnings += "could not remove $path: ${error.message.orEmpty()}" }
}

private fun desktopPlan(
  home: Path,
  binDir: Path,
  desktopAppDir: String?,
  environment: Map<String, String>,
): DesktopRemoval {
  val os = currentOs()
  val appDir = desktopAppDir?.let(Path::of) ?: defaultDesktopAppDir(home, environment, os)
  val executable = when (os) {
    DesktopOs.WINDOWS -> appDir.resolve("SkillBill.exe")
    else -> appDir.resolve("bin/skillbill-desktop")
  }
  val launcher = when (os) {
    DesktopOs.WINDOWS -> null
    else -> LauncherRemoval(binDir.resolve("skillbill-desktop"), executable)
  }
  val dataHome = environment["XDG_DATA_HOME"]?.let(Path::of) ?: home.resolve(".local/share")
  val linuxFiles = if (os == DesktopOs.LINUX) {
    listOf(
      dataHome.resolve("applications/skillbill.desktop"),
      dataHome.resolve("icons/hicolor/256x256/apps/skillbill.png"),
    )
  } else {
    emptyList()
  }
  val windowsLauncher = if (os == DesktopOs.WINDOWS) {
    listOf(binDir.resolve("skillbill-desktop.cmd"))
  } else {
    emptyList()
  }
  return DesktopRemoval(launcher = launcher, files = linuxFiles + windowsLauncher, directories = listOf(appDir))
}

private fun defaultDesktopAppDir(home: Path, environment: Map<String, String>, os: DesktopOs): Path = when (os) {
  DesktopOs.MAC -> Path.of("/Applications/SkillBill.app")
  DesktopOs.WINDOWS -> environment["LOCALAPPDATA"]?.let(Path::of)
    ?.resolve("SkillBill/Desktop/SkillBill")
    ?: home.resolve("AppData/Local/SkillBill/Desktop/SkillBill")
  DesktopOs.LINUX -> (environment["XDG_DATA_HOME"]?.let(Path::of) ?: home.resolve(".local/share"))
    .resolve("skillbill/desktop/SkillBill")
}

private fun currentOs(): DesktopOs {
  val osName = System.getProperty("os.name").lowercase()
  return when {
    "mac" in osName || "darwin" in osName -> DesktopOs.MAC
    "win" in osName -> DesktopOs.WINDOWS
    else -> DesktopOs.LINUX
  }
}

private enum class DesktopOs {
  LINUX,
  MAC,
  WINDOWS,
}

private data class LauncherRemoval(
  val path: Path,
  val expectedTarget: Path,
)

private data class DesktopRemoval(
  val launcher: LauncherRemoval?,
  val files: List<Path>,
  val directories: List<Path>,
)

private data class UninstallPlan(
  val home: Path,
  val stateRoot: Path,
  val skillNames: List<String>,
  val legacyNames: List<String>,
  val agentTargets: List<Path>,
  val nativeSourceRoots: List<Path>,
  val mcpAgents: List<String>,
  val launchers: List<LauncherRemoval>,
  val desktop: DesktopRemoval,
) {
  fun confirmationText(): String = buildString {
    appendLine("This will uninstall Skill Bill from:")
    appendLine("- ${agentTargets.size} agent target directories")
    appendLine("- ${mcpAgents.size} MCP configurations")
    appendLine("- $stateRoot")
    append("Continue? [y/N] ")
  }

  fun toText(status: String): String = buildString {
    appendLine("uninstall_status: $status")
    appendLine("state_root: $stateRoot")
    appendLine("agent_targets: ${agentTargets.size}")
    appendLine("skill_names: ${skillNames.size}")
  }

  fun toPayload(
    status: String,
    removed: List<String>,
    skipped: List<String>,
    warnings: List<String>,
  ): Map<String, Any?> = linkedMapOf(
    "status" to status,
    "state_root" to stateRoot.toString(),
    "skill_names" to skillNames,
    "legacy_names" to legacyNames,
    "agent_targets" to agentTargets.map(Path::toString),
    "mcp_agents" to mcpAgents,
    "launchers" to launchers.map {
      mapOf("path" to it.path.toString(), "expected_target" to it.expectedTarget.toString())
    },
    "desktop" to mapOf(
      "launcher" to desktop.launcher?.path?.toString(),
      "files" to desktop.files.map(Path::toString),
      "directories" to desktop.directories.map(Path::toString),
    ),
    "removed" to removed,
    "skipped" to skipped,
    "warnings" to warnings,
  )
}

private data class UninstallResult(
  val status: String,
  val removed: List<String>,
  val skipped: List<String>,
  val warnings: List<String>,
) {
  fun toText(): String = buildString {
    appendLine("uninstall_status: $status")
    appendLine("removed: ${removed.size}")
    appendLine("skipped: ${skipped.size}")
    if (warnings.isNotEmpty()) {
      appendLine("warnings:")
      warnings.forEach { warning -> appendLine("- $warning") }
    }
  }

  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "status" to status,
    "removed" to removed,
    "skipped" to skipped,
    "warnings" to warnings,
  )
}
