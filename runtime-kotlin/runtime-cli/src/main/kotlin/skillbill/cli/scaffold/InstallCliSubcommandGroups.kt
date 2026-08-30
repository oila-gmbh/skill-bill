package skillbill.cli.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.cli.install.InstallApplyCommand
import skillbill.cli.install.InstallApplyExternalAddonsCommand
import skillbill.cli.install.InstallClaudeAgentsPathCommand
import skillbill.cli.install.InstallClaudeRootsCommand
import skillbill.cli.install.InstallCleanupAgentTargetCommand
import skillbill.cli.install.InstallCodexAgentsPathCommand
import skillbill.cli.install.InstallCodexRootsCommand
import skillbill.cli.install.InstallCursorAgentsPathCommand
import skillbill.cli.install.InstallJunieAgentsPathCommand
import skillbill.cli.install.InstallPlanCommand
import skillbill.cli.install.InstallReconcileCommand
import skillbill.cli.install.InstallRegisterMcpCommand
import skillbill.cli.install.InstallReplayLastSelectionCommand
import skillbill.cli.install.InstallUnregisterMcpCommand

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

@Inject
class ScaffoldAuthoringReadCliSubcommands(
  val list: ListSkillsCommand,
  val show: ShowSkillCommand,
  val explain: ExplainSkillCommand,
  val validate: ValidateSkillCommand,
)

@Inject
class ScaffoldAuthoringWriteCliSubcommands(
  val upgrade: UpgradeSkillsCommand,
  val render: RenderSkillsCommand,
  val edit: EditSkillCommand,
  val fill: FillSkillCommand,
)

@Inject
class ScaffoldNewCliSubcommands(
  val newSkill: NewSkillCommand,
  val newAlias: NewCommand,
  val createAndFill: CreateAndFillCommand,
  val newAddon: NewAddonCommand,
)
