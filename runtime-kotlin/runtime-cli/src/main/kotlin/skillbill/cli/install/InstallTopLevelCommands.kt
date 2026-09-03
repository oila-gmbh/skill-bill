package skillbill.cli.install

import com.github.ajalt.clikt.core.subcommands
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.DocumentedNoOpCliCommand

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
