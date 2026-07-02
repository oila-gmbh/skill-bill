package skillbill.cli.config

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.SpecType
import skillbill.config.model.parseCodeReviewParallelAgent
import skillbill.config.model.parseSpecType
import skillbill.error.ShellContentContractException
import skillbill.install.model.InstallAgent
import java.nio.file.Path

@Inject
class ConfigCommand(
  resolveSpecTypeCommand: ConfigResolveSpecTypeCommand,
  resolveParallelAgentCommand: ConfigResolveParallelAgentCommand,
  resolveExternalAddonsCommand: ConfigResolveExternalAddonsCommand,
) : DocumentedNoOpCliCommand(
  "config",
  "Inspect resolved repo-local configuration (.skill-bill/config.yaml).",
) {
  init {
    subcommands(resolveSpecTypeCommand, resolveParallelAgentCommand, resolveExternalAddonsCommand)
  }
}

/**
 * Read-only surface over the single spec-source precedence helper. `--arg` carries the
 * `service:` override (`local`/`linear`); a blank value or `default` defers to config.
 * Prints the resolved id (`local`/`linear`) on stdout. A malformed/unreadable config
 * loud-fails with a non-zero exit carrying the typed contract-error message.
 */
@Inject
class ConfigResolveSpecTypeCommand(
  private val configResolutionService: ConfigResolutionService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "resolve-spec-type",
  "Resolve the effective spec-source mode (arg > config spec_type > local).",
) {
  private val arg by option(
    "--arg",
    help = "The service override value (local|linear). Blank or 'default' defers to config.",
  ).default("")
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root whose .skill-bill/config.yaml is read.",
  ).default(".")

  override fun run() {
    val explicit = resolveExplicit() ?: return
    val resolved = try {
      configResolutionService.resolveSpecType(Path.of(repoRoot), explicit.value)
    } catch (error: ShellContentContractException) {
      state.completeText("${error.message}\n", failurePayload(error.message), exitCode = 1)
      return
    }
    state.completeText(
      "${resolved.id}\n",
      mapOf("status" to "ok", "spec_type" to resolved.id),
    )
  }

  /**
   * Returns the explicit override wrapped to distinguish "no override" (defer to config) from a
   * bad arg (null result, after surfacing a non-zero failure). A blank or `default` arg means no
   * override; a non-blank unrecognized value loud-fails rather than silently deferring.
   */
  private fun resolveExplicit(): ExplicitArg? {
    val normalized = arg.trim().lowercase()
    if (normalized.isEmpty() || normalized == "default") return ExplicitArg(null)
    return parseSpecType(normalized)?.let { parsed -> ExplicitArg(parsed) }
      ?: run {
        state.completeText(
          "Unrecognized service value '$arg'. Supported: ${SpecType.supportedIds.joinToString()}, default.\n",
          failurePayload("Unrecognized service value '$arg'."),
          exitCode = 1,
        )
        null
      }
  }

  private fun failurePayload(message: String?): Map<String, Any?> =
    mapOf("status" to "failed", "error" to message.orEmpty())

  private data class ExplicitArg(
    val value: SpecType?,
  )
}

/**
 * Read-only surface over the code-review parallel-agent precedence helper. `--arg` carries the
 * `parallel:` override (a supported agent id or `none`); a blank value or `default` defers to
 * config. Prints the resolved agent id (`none` means single-lane) on stdout. A malformed/unreadable
 * config or an unrecognized config value loud-fails with a non-zero exit carrying the typed
 * contract-error message.
 */
@Inject
class ConfigResolveParallelAgentCommand(
  private val configResolutionService: ConfigResolutionService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "resolve-parallel-agent",
  "Resolve the effective code-review lane-2 agent (parallel arg > config code_review_parallel_agent > none).",
) {
  private val arg by option(
    "--arg",
    help = "The parallel override value (a supported agent id or none). Blank or 'default' defers to config.",
  ).default("")
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root whose .skill-bill/config.yaml is read.",
  ).default(".")

  override fun run() {
    val explicit = resolveExplicit() ?: return
    val resolved = try {
      configResolutionService.resolveCodeReviewParallelAgent(Path.of(repoRoot), explicit.value)
    } catch (error: ShellContentContractException) {
      state.completeText("${error.message}\n", failurePayload(error.message), exitCode = 1)
      return
    }
    state.completeText(
      "$resolved\n",
      mapOf("status" to "ok", "code_review_parallel_agent" to resolved),
    )
  }

  /**
   * Returns the explicit override wrapped to distinguish "no override" (defer to config) from a
   * bad arg (null result, after surfacing a non-zero failure). A blank or `default` arg means no
   * override; a non-blank unrecognized value loud-fails rather than silently deferring.
   */
  private fun resolveExplicit(): ExplicitArg? {
    val normalized = arg.trim().lowercase()
    if (normalized.isEmpty() || normalized == "default") return ExplicitArg(null)
    return parseCodeReviewParallelAgent(normalized)?.let { parsed -> ExplicitArg(parsed) }
      ?: run {
        val supported = (InstallAgent.supportedIds + RepoLocalConfig.NO_PARALLEL_AGENT).joinToString()
        state.completeText(
          "Unrecognized parallel agent value '$arg'. Supported: $supported, default.\n",
          failurePayload("Unrecognized parallel agent value '$arg'."),
          exitCode = 1,
        )
        null
      }
  }

  private fun failurePayload(message: String?): Map<String, Any?> =
    mapOf("status" to "failed", "error" to message.orEmpty())

  private data class ExplicitArg(
    val value: String?,
  )
}
