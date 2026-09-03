package skillbill.cli.scaffold

import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.install.model.InstallAgent

internal fun addOnWizardPayload(state: CliRunState, inputs: CliRunInputs): Map<String, Any?> = buildMap {
  putScaffoldBase("add-on")
  put("platform", promptRequired(state, inputs, "Platform slug"))
  put("name", promptRequired(state, inputs, "Add-on name"))
  when (normalizeAddOnLocationMode(promptDefault(state, inputs, "Add-on source (native/external)", "native"))) {
    "native" -> Unit
    "external" -> put("addon_location_path", promptRequired(state, inputs, "External add-on source path"))
  }
  promptOptional(state, inputs, "Description").ifNotBlank { description -> put("description", description) }
}

internal fun agentAddonWizardPayload(state: CliRunState, inputs: CliRunInputs): Map<String, Any?> = buildMap {
  putScaffoldBase("agent-addon")
  put("slug", promptRequired(state, inputs, "Agent add-on slug"))
  put("description", promptRequired(state, inputs, "Description"))
  inputs.liveStdout("Supported agents: ${InstallAgent.supportedIds.joinToString(", ")}\n")
  put("agent_ids", requiredCommaSeparated(state, inputs, "Agent IDs (comma-separated)"))
  put("consumers", requiredCommaSeparated(state, inputs, "Consumers (comma-separated, supported: bill-feature)"))
}

internal fun platformPackWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> = buildMap {
  putScaffoldBase("platform-pack")
  val platform = promptRequired(state, inputs, "Platform slug")
  put("platform", platform)
  promptOptional(state, inputs, "Display name").ifNotBlank { displayName -> put("display_name", displayName) }
  promptOptional(state, inputs, "Description").ifNotBlank { description -> put("description", description) }
  promptRoutingSignals(state, inputs, platform, platform in platformPackPresets)
    .ifNotEmpty { signals -> put("routing_signals", mapOf("strong" to signals)) }
}

internal fun assistedPlatformPackWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> {
  val platformInput = promptRequired(state, inputs, "Language or platform")
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

internal fun MutableMap<String, Any?>.putScaffoldBase(kind: String) {
  put("scaffold_payload_version", "1.0")
  put("kind", kind)
}

internal inline fun String.ifNotBlank(block: (String) -> Unit) {
  if (isNotBlank()) block(this)
}

internal inline fun <T> List<T>.ifNotEmpty(block: (List<T>) -> Unit) {
  if (isNotEmpty()) block(this)
}
