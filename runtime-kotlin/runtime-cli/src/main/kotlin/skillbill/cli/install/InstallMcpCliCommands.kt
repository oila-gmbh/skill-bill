package skillbill.cli.install

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.McpRegistrationService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.install.model.ClaudeMcpProfileFailure
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import java.nio.file.Path

@Inject
class InstallRegisterMcpCommand(
  private val state: CliRunState,
  private val mcpRegistrationService: McpRegistrationService,
) : DocumentedCliCommand("register-mcp", "Register Skill Bill's packaged Kotlin MCP server for one agent.") {
  private val agent by argument(help = "Agent name.")
  private val runtimeMcpBin by option("--runtime-mcp-bin", help = "Packaged runtime-mcp bin script.").required()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("register-mcp")) {
      return
    }
    val result = mcpRegistrationService.registerMcp(agent, Path.of(runtimeMcpBin), state.userHome)
    state.completeText(mcpProfilePathsText(result), mcpProfilesMap(agent, result))
  }
}

@Inject
class InstallUnregisterMcpCommand(
  private val state: CliRunState,
  private val mcpRegistrationService: McpRegistrationService,
) : DocumentedCliCommand("unregister-mcp", "Remove Skill Bill MCP registration for one agent.") {
  private val agent by argument(help = "Agent name.")

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("unregister-mcp")) {
      return
    }
    val result = try {
      mcpRegistrationService.unregisterMcp(agent, state.userHome)
    } catch (error: ClaudeMcpProfileFailure) {
      val removed = changedProfilePathsText(error.succeeded)
      if (removed.isNotEmpty()) {
        state.liveStdout("$removed\n")
      }
      throw error
    }
    state.completeText(mcpProfilePathsText(result), mcpProfilesMap(agent, result))
  }
}

private fun mcpProfilePathsText(result: McpMutationResult): String = if (result.profiles.isEmpty()) {
  result.configPath.toString()
} else {
  changedProfilePathsText(result.profiles)
}

private fun changedProfilePathsText(profiles: List<McpProfileOutcome>): String = profiles
  .filter { it.changed }
  .joinToString("\n") { it.configPath.toString() }

private fun mcpProfilesMap(agent: String, result: McpMutationResult): Map<String, Any?> = mapOf(
  "agent" to agent,
  "changed" to result.changed,
  "profiles" to result.profiles.map { profile ->
    mapOf("config_path" to profile.configPath.toString(), "changed" to profile.changed)
  },
)
