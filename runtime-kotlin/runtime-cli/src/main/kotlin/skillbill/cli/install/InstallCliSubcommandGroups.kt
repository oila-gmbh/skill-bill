package skillbill.cli.install

import me.tatarka.inject.annotations.Inject

@Inject
class InstallPlanCliSubcommands(
  val plan: InstallPlanCommand,
  val apply: InstallApplyCommand,
  val applyExternalAddons: InstallApplyExternalAddonsCommand,
  val reconcile: InstallReconcileCommand,
  val replayLastSelection: InstallReplayLastSelectionCommand,
)

@Inject
class InstallAgentDiscoveryCliSubcommands(
  val agentPath: InstallAgentPathCommand,
  val detectAgents: InstallDetectAgentsCommand,
  val claudeRoots: InstallClaudeRootsCommand,
  val codexRoots: InstallCodexRootsCommand,
)

@Inject
class InstallAgentPathsCliSubcommands(
  val linkSkill: InstallLinkSkillCommand,
  val codexAgentsPath: InstallCodexAgentsPathCommand,
  val claudeAgentsPath: InstallClaudeAgentsPathCommand,
  val junieAgentsPath: InstallJunieAgentsPathCommand,
  val cursorAgentsPath: InstallCursorAgentsPathCommand,
  val cleanupAgentTarget: InstallCleanupAgentTargetCommand,
)

@Inject
class InstallNativeAgentCliSubcommands(
  claude: NativeAgentClaudeCliCommands,
  codex: NativeAgentCodexCliCommands,
  junie: NativeAgentJunieCliCommands,
  cursor: NativeAgentCursorCliCommands,
) {
  val linkClaudeAgents = claude.link
  val unlinkClaudeAgents = claude.unlink
  val linkCodexAgents = codex.link
  val unlinkCodexAgents = codex.unlink
  val linkJunieAgents = junie.link
  val unlinkJunieAgents = junie.unlink
  val linkCursorAgents = cursor.link
  val unlinkCursorAgents = cursor.unlink
}

@Inject
class InstallMcpCliSubcommands(
  val registerMcp: InstallRegisterMcpCommand,
  val unregisterMcp: InstallUnregisterMcpCommand,
)
