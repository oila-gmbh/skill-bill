package skillbill.cli.agentaddon

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.featuretask.parseAgentAddonSelection
import skillbill.error.ShellContentContractException
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import java.nio.file.Path

@Inject
class AgentAddonCommand(
  resolveSelection: AgentAddonResolveSelectionCommand,
  verifySelection: AgentAddonVerifySelectionCommand,
) : DocumentedNoOpCliCommand("agent-addon", "Resolve and verify explicit agent add-on selections.") {
  init {
    subcommands(resolveSelection, verifySelection)
  }
}

@Inject
class AgentAddonResolveSelectionCommand(
  private val resolver: AgentAddonSelectionPort,
  private val externalSourceConfig: ExternalAgentAddonSourceConfigPort,
  private val state: CliRunState,
) : DocumentedCliCommand("resolve-selection", "Resolve ordered agent-addon:<slug> tokens without side effects.") {
  private val tokens by option("--token", help = "Ordered agent-addon:<slug> token.").multiple()
  private val receivingAgents by option("--receiving-agent", help = "Agent that will receive the selection.").multiple()
  private val repoRoot by option("--repo-root").default(".")
  private val format by formatOption()

  override fun run() {
    val slugs = tokens.map { token ->
      if (!token.startsWith(PREFIX) || token.length == PREFIX.length) {
        throw UsageError("Malformed agent add-on token '$token'; expected agent-addon:<slug>.")
      }
      token.removePrefix(PREFIX)
    }
    complete {
      val selection = resolver.resolveInitial(
        Path.of(repoRoot).toAbsolutePath().normalize(),
        slugs,
        AgentAddonConsumer.BILL_FEATURE,
        receivingAgents,
        externalSourceConfig.readExternalAgentAddonSources(
          ExternalAgentAddonSourceConfigRequest(state.userHome, state.environment),
        ).sources.map { it.path },
      )
      linkedMapOf(
        "contract_version" to "0.1",
        "entries" to selection.entries.map { entry ->
          linkedMapOf(
            "slug" to entry.persisted.slug,
            "source_identity" to entry.persisted.sourceIdentity,
            "content_sha256" to entry.persisted.contentSha256,
            "description" to entry.description,
          )
        },
      )
    }
  }

  private fun complete(block: () -> Map<String, Any?>) {
    try {
      state.complete(block(), format)
    } catch (error: ShellContentContractException) {
      state.complete(mapOf("status" to "failed", "error" to error.message.orEmpty()), format, exitCode = 1)
    }
  }
}

@Inject
class AgentAddonVerifySelectionCommand(
  private val resolver: AgentAddonSelectionPort,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "verify-selection",
  "Verify persisted identities and render the guarded prompt section.",
) {
  private val selectionJson by option(
    "--selection-json",
    help = "Strict resolved selection JSON.",
  ).default(EMPTY_SELECTION)
  private val receivingAgents by option("--receiving-agent").multiple()
  private val format by formatOption()

  override fun run() {
    try {
      val hydrated = resolver.verifyPersisted(
        parseAgentAddonSelection(selectionJson),
        AgentAddonConsumer.BILL_FEATURE,
        receivingAgents,
      )
      state.complete(
        linkedMapOf(
          "contract_version" to "0.1",
          "entries" to hydrated.entries.map { entry ->
            linkedMapOf(
              "slug" to entry.persisted.slug,
              "source_identity" to entry.persisted.sourceIdentity,
              "content_sha256" to entry.persisted.contentSha256,
              "description" to entry.description,
            )
          },
          "prompt_section" to AgentAddonPromptFormatter.format(hydrated),
        ),
        format,
      )
    } catch (error: ShellContentContractException) {
      state.complete(mapOf("status" to "failed", "error" to error.message.orEmpty()), format, exitCode = 1)
    }
  }
}

private const val PREFIX = "agent-addon:"
private const val EMPTY_SELECTION = "{\"contract_version\":\"0.1\",\"entries\":[]}"
