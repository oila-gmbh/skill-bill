package skillbill.cli.config

import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.error.ShellContentContractException

@Inject
class ConfigResolveExternalAddonsCommand(
  private val service: ExternalAddonOverlayService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "resolve-external-addons",
  "Resolve external addon sources from the machine-global ~/.skill-bill/config.json.",
) {
  override fun run() {
    val sources = try {
      service.resolveSources(state.userHome, state.environment)
    } catch (error: ShellContentContractException) {
      state.completeText(
        "${error.message}\n",
        mapOf("status" to "failed", "error" to error.message.orEmpty()),
        exitCode = 1,
      )
      return
    }
    val text = sources.joinToString("") { source -> "${source.platform}\t${source.path}\n" }
    state.completeText(
      text,
      mapOf(
        "status" to "ok",
        "sources" to sources.map { source ->
          mapOf("platform" to source.platform, "path" to source.path.toString())
        },
      ),
    )
  }
}
