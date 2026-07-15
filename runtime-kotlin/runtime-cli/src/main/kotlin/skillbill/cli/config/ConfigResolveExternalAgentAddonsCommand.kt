package skillbill.cli.config

import me.tatarka.inject.annotations.Inject
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.error.ShellContentContractException
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest

@Inject
class ConfigResolveExternalAgentAddonsCommand(
  private val config: ExternalAgentAddonSourceConfigPort,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "resolve-external-agent-addons",
  "Resolve external agent add-on sources from the machine-global config.json.",
) {
  override fun run() {
    val sources = try {
      config.readExternalAgentAddonSources(
        ExternalAgentAddonSourceConfigRequest(state.userHome, state.environment),
      ).sources
    } catch (error: ShellContentContractException) {
      state.completeText(
        "${error.message}\n",
        mapOf("status" to "failed", "error" to error.message.orEmpty()),
        exitCode = 1,
      )
      return
    }
    val text = sources.joinToString("") { source -> "${source.path}\n" }
    state.completeText(
      text,
      mapOf(
        "status" to "ok",
        "sources" to sources.map { source -> mapOf("path" to source.path.toString()) },
      ),
    )
  }
}
