package skillbill.cli.scaffold

import skillbill.cli.core.CliRunState
import skillbill.install.model.InstallAgent

internal fun agentAddonWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("agent-addon")
  put("slug", promptRequired(state, "Agent add-on slug"))
  put("description", promptRequired(state, "Description"))
  state.liveStdout("Supported agents: ${InstallAgent.supportedIds.joinToString(", ")}\n")
  put("agent_ids", requiredCommaSeparated(state, "Agent IDs (comma-separated)"))
  put("consumers", requiredCommaSeparated(state, "Consumers (comma-separated, supported: bill-feature)"))
}

internal fun platformPackWizardPayload(
  state: CliRunState,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> = buildMap {
  putScaffoldBase("platform-pack")
  val platform = promptRequired(state, "Platform slug")
  put("platform", platform)
  promptOptional(state, "Display name").ifNotBlank { displayName -> put("display_name", displayName) }
  promptOptional(state, "Description").ifNotBlank { description -> put("description", description) }
  promptRoutingSignals(state, platform, platform in platformPackPresets)
    .ifNotEmpty { signals -> put("routing_signals", mapOf("strong" to signals)) }
}

internal fun assistedPlatformPackWizardPayload(
  state: CliRunState,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> {
  val platformInput = promptRequired(state, "Language or platform")
  return assistedPlatformPackPayload(platformInput, platformPackPresets)
}

internal fun assistedPlatformPackPayload(
  platformInput: String,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> = buildMap {
  val profile = assistedPlatformProfile(platformInput)
  val displayName = platformPackPresets[profile.slug] ?: profile.displayName
  putScaffoldBase("platform-pack")
  put("platform", profile.slug)
  put("display_name", displayName)
  put("description", "$displayName platform pack for code review and quality checks.")
  if (profile.slug !in platformPackPresets) {
    put("routing_signals", mapOf("strong" to profile.strongSignals))
  }
}

internal inline fun String.ifNotBlank(block: (String) -> Unit) {
  if (isNotBlank()) block(this)
}

internal inline fun <T> List<T>.ifNotEmpty(block: (List<T>) -> Unit) {
  if (isNotEmpty()) block(this)
}

internal fun parseCommaSeparated(value: String): List<String> =
  value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
