package skillbill.infrastructure.fs

import skillbill.contracts.JsonSupport
import skillbill.install.model.InstallAgent
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.SharedInstallSelection

internal fun SharedInstallSelection.toInstallSelectionJson(): String = JsonSupport.mapToJsonString(toWireMap())

private fun SharedInstallSelection.toWireMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to INSTALL_SELECTION_CONTRACT_VERSION,
  "selected_agents" to selectedAgents.map(InstallAgent::id).sorted(),
  "platform_pack_selection" to platformPackSelection.toWireMap(),
  "telemetry_level" to telemetryLevel.id,
  "mcp_registration" to mcpRegistrationChoice.toWireMap(),
)

private fun PlatformPackSelection.toWireMap(): Map<String, Any?> = linkedMapOf(
  "mode" to mode.name.lowercase(),
  "selected_slugs" to selectedSlugs.sorted(),
)

private fun McpRegistrationChoice.toWireMap(): Map<String, Any?> = linkedMapOf(
  "register" to register,
  "runtime_mcp_bin" to runtimeMcpBin?.toString(),
)

internal const val INSTALL_SELECTION_CONTRACT_VERSION = "1.0"

internal val INSTALL_SELECTION_KEYS =
  setOf("contract_version", "selected_agents", "platform_pack_selection", "telemetry_level", "mcp_registration")
internal val PLATFORM_PACK_SELECTION_KEYS = setOf("mode", "selected_slugs")
internal val MCP_REGISTRATION_KEYS = setOf("register", "runtime_mcp_bin")
