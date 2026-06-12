package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.datastore.DesktopFirstRunPreferences
import skillbill.desktop.core.domain.model.FirstRunPlatformSelectionMode
import skillbill.desktop.core.domain.model.FirstRunSetupDiscovery
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.FirstRunSetupStep
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel

internal fun FirstRunSetupRequest.toFirstRunSetupState(): FirstRunSetupState = FirstRunSetupState(
  selectedAgentIds = selectedAgentIds,
  selectedPlatformSlugs = selectedPlatformSlugs,
  platformSelectionMode = platformSelectionMode,
  telemetryLevel = telemetryLevel,
  registerMcp = registerMcp,
)

internal fun FirstRunSetupState.applyDiscovery(
  discovery: FirstRunSetupDiscovery,
  preferredRequest: FirstRunSetupRequest?,
): FirstRunSetupState {
  val preferredAgents = preferredRequest?.selectedAgentIds?.takeIf(Set<String>::isNotEmpty)
  val selectedAgents = preferredAgents ?: discovery.agents
    .filter { option -> option.selected }
    .mapTo(mutableSetOf()) { option -> option.agentId }
  val selectedPlatforms = when (preferredRequest?.platformSelectionMode) {
    FirstRunPlatformSelectionMode.ALL -> discovery.platformPacks.mapTo(mutableSetOf()) { pack -> pack.slug }
    FirstRunPlatformSelectionMode.SELECTED -> preferredRequest.selectedPlatformSlugs
    FirstRunPlatformSelectionMode.NONE -> emptySet()
    null -> discovery.selectedPlatformSlugs
  }
  return copy(
    busy = false,
    discoveryLoaded = true,
    errorMessage = null,
    agentOptions = discovery.agents.map { option ->
      option.copy(selected = option.agentId in selectedAgents)
    },
    platformPacks = discovery.platformPacks.map { pack ->
      pack.copy(selected = pack.slug in selectedPlatforms)
    },
    selectedAgentIds = selectedAgents,
    selectedPlatformSlugs = selectedPlatforms,
    platformSelectionMode = preferredRequest?.platformSelectionMode
      ?: selectedPlatforms.toFirstRunPlatformSelectionMode(),
    telemetryLevel = preferredRequest?.telemetryLevel ?: FirstRunTelemetryLevel.default,
    registerMcp = preferredRequest?.registerMcp ?: true,
  )
}

internal fun Set<String>.toFirstRunPlatformSelectionMode(): FirstRunPlatformSelectionMode = if (isEmpty()) {
  FirstRunPlatformSelectionMode.NONE
} else {
  FirstRunPlatformSelectionMode.SELECTED
}

internal fun DesktopFirstRunPreferences.toLegacySetupRequestOrNull(): FirstRunSetupRequest? {
  if (!completed || selectedAgentIds.isEmpty()) {
    return null
  }
  return FirstRunSetupRequest(
    selectedAgentIds = selectedAgentIds,
    selectedPlatformSlugs = selectedPlatformSlugs,
    telemetryLevel = FirstRunTelemetryLevel.fromId(telemetryLevelId),
    registerMcp = registerMcp,
  )
}

internal fun FirstRunSetupStep.next(): FirstRunSetupStep = when (this) {
  FirstRunSetupStep.AGENTS -> FirstRunSetupStep.PLATFORM_PACKS
  FirstRunSetupStep.PLATFORM_PACKS -> FirstRunSetupStep.PREFERENCES
  FirstRunSetupStep.PREFERENCES -> FirstRunSetupStep.APPLY
  FirstRunSetupStep.APPLY -> FirstRunSetupStep.RESULT
  FirstRunSetupStep.RESULT -> FirstRunSetupStep.RESULT
}

internal fun FirstRunSetupStep.previous(): FirstRunSetupStep = when (this) {
  FirstRunSetupStep.AGENTS -> FirstRunSetupStep.AGENTS
  FirstRunSetupStep.PLATFORM_PACKS -> FirstRunSetupStep.AGENTS
  FirstRunSetupStep.PREFERENCES -> FirstRunSetupStep.PLATFORM_PACKS
  FirstRunSetupStep.APPLY -> FirstRunSetupStep.PREFERENCES
  FirstRunSetupStep.RESULT -> FirstRunSetupStep.PREFERENCES
}
