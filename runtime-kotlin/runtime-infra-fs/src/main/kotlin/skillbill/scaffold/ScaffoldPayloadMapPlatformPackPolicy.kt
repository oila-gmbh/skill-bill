package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.PLATFORM_PACK_PRESET_DESCRIPTORS
import skillbill.scaffold.policy.displayNameFromSlug
import skillbill.scaffold.policy.model.PlatformPackDefaults
import skillbill.scaffold.policy.model.PlatformPackSelection

/**
 * SKILL-52.2 subtask 2 (Task 11): platform-pack-resolution helpers split out of
 * `ScaffoldPayloadMapPolicy` so each file stays under the detekt `TooManyFunctions` threshold
 * and each function stays under the per-function `ThrowsCount` cap. See the doc comment in
 * `ScaffoldPayloadMapPolicy.kt` for the relocation rationale.
 */

internal fun resolvePlatformPackSelection(payload: Map<String, Any?>): PlatformPackSelection {
  val skeletonMode = requireStringOrDefaultMap(payload, "skeleton_mode", "full")
  val specialistAreas = payload["specialist_areas"]?.let { value ->
    validateSpecialistAreas(requireStringListPayload(value, "specialist_areas"))
  }
  enforceSkeletonModeValue(skeletonMode)
  enforceSkeletonModeAreasExclusive(payload, specialistAreas)
  val selectedAreas =
    specialistAreas?.let { requested ->
      APPROVED_CODE_REVIEW_AREAS.sorted().filter { area -> area in requested.toSet() }
    }
      ?: if (skeletonMode == "full") APPROVED_CODE_REVIEW_AREAS.sorted() else emptyList()
  return PlatformPackSelection(selectedAreas = selectedAreas)
}

private fun validateSpecialistAreas(areas: List<String>): List<String> {
  val unknown = areas.filter { area -> area !in APPROVED_CODE_REVIEW_AREAS }
  if (unknown.isNotEmpty()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'specialist_areas' contains unknown areas $unknown; " +
        "approved areas: $APPROVED_CODE_REVIEW_AREAS.",
    )
  }
  return areas
}

private fun enforceSkeletonModeValue(skeletonMode: String) {
  if (skeletonMode !in setOf("starter", "full")) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'skeleton_mode' must be one of [full, starter] when provided.",
    )
  }
}

private fun enforceSkeletonModeAreasExclusive(payload: Map<String, Any?>, specialistAreas: List<String>?) {
  if (specialistAreas != null && payload.containsKey("skeleton_mode")) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload may not provide both 'skeleton_mode' and 'specialist_areas'; " +
        "choose one specialist selection mode.",
    )
  }
}

internal fun resolvePlatformPackDefaults(payload: Map<String, Any?>, platform: String): PlatformPackDefaults {
  val preset = PLATFORM_PACK_PRESET_DESCRIPTORS[platform]
  val routing = payload["routing_signals"] as? Map<*, *>
  val strong = routing?.get("strong")?.let { requireStringListPayload(it, "routing_signals.strong") }
  val tieBreakers = routing?.get("tie_breakers")?.let { requireStringListPayload(it, "routing_signals.tie_breakers") }
  val resolvedStrong = strong ?: preset?.strongSignals.orEmpty()
  val resolvedTieBreakers = tieBreakers ?: preset?.tieBreakers.orEmpty()
  enforceRoutingSignalsStrongNonEmpty(resolvedStrong, preset != null, platform)
  val displayName = requireStringOrDefaultMap(
    payload,
    "display_name",
    preset?.displayName ?: displayNameFromSlug(platform),
  )
  return PlatformPackDefaults(
    displayName = displayName,
    strongSignals = resolvedStrong,
    tieBreakers = resolvedTieBreakers,
    presetUsed = preset != null && routing == null,
  )
}

private fun enforceRoutingSignalsStrongNonEmpty(strong: List<String>, hasPreset: Boolean, platform: String) {
  if (strong.isNotEmpty()) return
  val message = if (!hasPreset) {
    "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal " +
      "when no built-in platform preset exists for '$platform'."
  } else {
    "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal."
  }
  throw InvalidScaffoldPayloadError(message)
}
