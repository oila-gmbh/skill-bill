package skillbill.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.install.InstallCleanupOperations
import skillbill.install.InstallNativeAgentOperations
import skillbill.install.InstallOperations
import skillbill.launcher.McpRegistrationOperations
import java.nio.file.Path

@Inject
class InstallCleanupAgentTargetCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("cleanup-agent-target", "Remove Skill Bill symlinks and managed dirs from one agent path.") {
  private val targetDir by option("--target-dir", help = "Agent install directory.").required()
  private val skillNames by option("--skill-name", help = "Current skill name to remove.").multiple()
  private val legacyNames by option("--legacy-name", help = "Legacy skill name to remove.").multiple()
  private val marker by option("--marker", help = "Managed install marker file.").default(".skill-bill-install")

  override fun run() {
    val (removed, skipped) = InstallCleanupOperations.cleanupAgentTarget(
      targetDir = Path.of(targetDir),
      skillNames = skillNames,
      legacyNames = legacyNames,
      managedInstallMarker = marker,
    )
    state.completeText(
      (
        removed.map { path -> "removed\t$path" } +
          skipped.map { path -> "skipped\t$path" }
        ).joinToString("\n"),
      mapOf("removed" to removed.map(Path::toString), "skipped" to skipped.map(Path::toString)),
    )
  }
}

@Inject
class InstallCodexAgentsPathCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("codex-agents-path", "Print the Codex native subagent TOML directory.") {
  override fun run() {
    state.completeText(InstallOperations.codexAgentsPath(state.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallOpencodeAgentsPathCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("opencode-agents-path", "Print the OpenCode native subagent markdown directory.") {
  override fun run() {
    state.completeText(InstallOperations.opencodeAgentsPath(state.userHome).toString(), emptyMap())
  }
}

@Inject
class InstallLinkCodexAgentsCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("link-codex-agents", "Link Codex native subagent TOMLs from repo discovery roots.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    val links =
      InstallNativeAgentOperations.linkCodexAgents(
        platformPacksRoot = Path.of(platformPacks),
        skillsRoot = skills?.let(Path::of),
        home = state.userHome,
        selectedPlatforms = platforms.ifEmpty { null },
      )
    state.completeText(links.joinToString("\n"), mapOf("linked" to links.map(Path::toString)))
  }
}

@Inject
class InstallUnlinkCodexAgentsCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("unlink-codex-agents", "Remove Codex native subagent TOML symlinks from candidate dirs.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    val removed =
      InstallNativeAgentOperations.unlinkCodexAgents(
        platformPacksRoot = Path.of(platformPacks),
        skillsRoot = skills?.let(Path::of),
        home = state.userHome,
        selectedPlatforms = platforms.ifEmpty { null },
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkOpencodeAgentsCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("link-opencode-agents", "Link OpenCode native subagent markdown from repo discovery roots.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    val links =
      InstallNativeAgentOperations.linkOpencodeAgents(
        platformPacksRoot = Path.of(platformPacks),
        skillsRoot = skills?.let(Path::of),
        home = state.userHome,
        selectedPlatforms = platforms.ifEmpty { null },
      )
    state.completeText(links.joinToString("\n"), mapOf("linked" to links.map(Path::toString)))
  }
}

@Inject
class InstallUnlinkOpencodeAgentsCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("unlink-opencode-agents", "Remove OpenCode native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    val removed =
      InstallNativeAgentOperations.unlinkOpencodeAgents(
        platformPacksRoot = Path.of(platformPacks),
        skillsRoot = skills?.let(Path::of),
        home = state.userHome,
        selectedPlatforms = platforms.ifEmpty { null },
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallRegisterMcpCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("register-mcp", "Register Skill Bill's packaged Kotlin MCP server for one agent.") {
  private val agent by argument(help = "Agent name.")
  private val runtimeMcpBin by option("--runtime-mcp-bin", help = "Packaged runtime-mcp bin script.").required()

  override fun run() {
    val result = McpRegistrationOperations.register(agent, Path.of(runtimeMcpBin), state.userHome)
    state.completeText(result.configPath.toString(), mapOf("agent" to agent, "changed" to result.changed))
  }
}

@Inject
class InstallUnregisterMcpCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("unregister-mcp", "Remove Skill Bill MCP registration for one agent.") {
  private val agent by argument(help = "Agent name.")

  override fun run() {
    val result = McpRegistrationOperations.unregister(agent, state.userHome)
    state.completeText(result.configPath.toString(), mapOf("agent" to agent, "changed" to result.changed))
  }
}
