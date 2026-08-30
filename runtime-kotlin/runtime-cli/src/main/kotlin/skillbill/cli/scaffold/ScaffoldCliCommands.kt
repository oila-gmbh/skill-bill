@file:Suppress("MaxLineLength")

package skillbill.cli.scaffold

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import me.tatarka.inject.annotations.Inject
import skillbill.cli.core.DocumentedNoOpCliCommand

@Inject
class ScaffoldTopLevelCommands(
  authoringRead: ScaffoldAuthoringReadCliSubcommands,
  authoringWrite: ScaffoldAuthoringWriteCliSubcommands,
  newCommands: ScaffoldNewCliSubcommands,
  installCommands: InstallTopLevelCommands,
) {
  val newSkill = newCommands.newSkill
  val newAlias = newCommands.newAlias
  val createAndFill = newCommands.createAndFill
  val newAddon = newCommands.newAddon
  val install = installCommands
  val commands: List<CliktCommand> =
    listOf(
      authoringRead.list,
      authoringRead.show,
      authoringRead.explain,
      authoringRead.validate,
      authoringWrite.upgrade,
      authoringWrite.render,
      authoringWrite.edit,
      authoringWrite.fill,
      newCommands.newSkill,
      newCommands.newAlias,
      newCommands.createAndFill,
      newCommands.newAddon,
      install.command,
    )
}

@Inject
class InstallTopLevelCommands(
  plan: InstallPlanCliSubcommands,
  discovery: InstallAgentDiscoveryCliSubcommands,
  agentPaths: InstallAgentPathsCliSubcommands,
  nativeAgents: InstallNativeAgentCliSubcommands,
  mcp: InstallMcpCliSubcommands,
) {
  val command: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand(
      "install",
      "Install-side primitives (agent paths, symlinks, native subagents, MCP registration).",
    ) {}
      .subcommands(
        plan.plan,
        plan.apply,
        plan.applyExternalAddons,
        plan.reconcile,
        plan.replayLastSelection,
        discovery.agentPath,
        discovery.detectAgents,
        discovery.claudeRoots,
        discovery.codexRoots,
        agentPaths.linkSkill,
        agentPaths.codexAgentsPath,
        agentPaths.claudeAgentsPath,
        agentPaths.junieAgentsPath,
        agentPaths.cursorAgentsPath,
        agentPaths.cleanupAgentTarget,
        nativeAgents.linkClaudeAgents,
        nativeAgents.unlinkClaudeAgents,
        nativeAgents.linkCodexAgents,
        nativeAgents.unlinkCodexAgents,
        nativeAgents.linkJunieAgents,
        nativeAgents.unlinkJunieAgents,
        nativeAgents.linkCursorAgents,
        nativeAgents.unlinkCursorAgents,
        mcp.registerMcp,
        mcp.unregisterMcp,
      )
}
