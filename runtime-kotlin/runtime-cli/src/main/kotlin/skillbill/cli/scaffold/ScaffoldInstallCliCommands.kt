package skillbill.cli.scaffold

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.scaffold.InstallAgentService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.install.refuseInstallMutationDuringGoalContinuation
import skillbill.cli.model.CliExecutionResult
import java.nio.file.Path

@Inject
class InstallAgentPathCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("agent-path", "Print the canonical install directory for a given agent.") {
  private val agent by argument(help = "Agent name.")

  override fun run() {
    val path = installAgentService.agentPath(agent, state.userHome, state.environment)
    state.result = CliExecutionResult(exitCode = 0, stdout = "$path\n")
  }
}

@Inject
class InstallDetectAgentsCommand(
  private val state: CliRunState,
  private val installAgentService: InstallAgentService,
) : DocumentedCliCommand("detect-agents", "List detected agents as 'name\\tpath' lines.") {
  override fun run() {
    val output =
      installAgentService.detectAgentTargets(state.userHome, state.environment)
        .joinToString(separator = "") { target -> "${target.name}\t${target.path}\n" }
    state.result = CliExecutionResult(exitCode = 0, stdout = output)
  }
}

@Inject
class InstallLinkSkillCommand(
  private val state: CliRunState,
  private val installService: InstallService,
) : DocumentedCliCommand(
  "link-skill",
  "Symlink a skill DIRECTORY into an agent's install directory.",
) {
  private val source by option("--source", help = "Skill directory to install.").required()
  private val targetDir by option("--target-dir", help = "Target install directory.").required()
  private val agent by option("--agent", help = "Optional agent name to label the install.").default("")
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root for content-managed skills; enables generated SKILL.md install staging.",
  )

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("link-skill")) {
      return
    }
    installService.linkSkill(
      source = Path.of(source),
      targetDir = Path.of(targetDir),
      agent = agent,
      repoRoot = repoRoot?.let(Path::of),
      home = state.userHome,
    )
    state.result = CliExecutionResult(exitCode = 0, stdout = "")
  }
}
