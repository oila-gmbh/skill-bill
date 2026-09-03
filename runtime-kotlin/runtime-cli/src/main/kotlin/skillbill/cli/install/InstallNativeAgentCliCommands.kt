package skillbill.cli.install

import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.NativeAgentInstallService
import skillbill.cli.kernel.CliRunState
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import java.nio.file.Path

@Inject
class InstallLinkClaudeAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-claude-agents", "Render and link Claude native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "link-claude-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(NativeAgentLinkProvider.CLAUDE, nativeAgentLinkRequest()),
    )
  }

  private fun nativeAgentLinkRequest(): NativeAgentLinkRequest = NativeAgentLinkRequest(
    platformPacksRoot = Path.of(platformPacks),
    skillsRoot = skills?.let(Path::of),
    home = inputs.userHome,
    selectedPlatforms = platforms.ifEmpty { null },
  )
}

@Inject
class InstallUnlinkClaudeAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-claude-agents", "Remove Claude native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unlink-claude-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.CLAUDE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = inputs.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkCodexAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-codex-agents", "Render and link Codex native subagent TOMLs from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "link-codex-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(
        NativeAgentLinkProvider.CODEX,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = inputs.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      ),
    )
  }
}

@Inject
class InstallUnlinkCodexAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-codex-agents", "Remove Codex native subagent TOML symlinks from candidate dirs.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unlink-codex-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.CODEX,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = inputs.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkJunieAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-junie-agents", "Render and link Junie native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "link-junie-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(
        NativeAgentLinkProvider.JUNIE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = inputs.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      ),
    )
  }
}

@Inject
class InstallUnlinkJunieAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-junie-agents", "Remove Junie native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unlink-junie-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.JUNIE,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = inputs.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkCursorAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("link-cursor-agents", "Render and link Cursor native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "link-cursor-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      nativeAgentInstallService.linkNativeAgents(
        NativeAgentLinkProvider.CURSOR,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = inputs.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      ),
    )
  }
}

@Inject
class InstallUnlinkCursorAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val nativeAgentInstallService: NativeAgentInstallService,
) : DocumentedCliCommand("unlink-cursor-agents", "Remove Cursor native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unlink-cursor-agents")) {
      return
    }
    val removed =
      nativeAgentInstallService.unlinkNativeAgents(
        NativeAgentLinkProvider.CURSOR,
        NativeAgentLinkRequest(
          platformPacksRoot = Path.of(platformPacks),
          skillsRoot = skills?.let(Path::of),
          home = inputs.userHome,
          selectedPlatforms = platforms.ifEmpty { null },
        ),
      )
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}
