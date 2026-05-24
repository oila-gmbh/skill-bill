package skillbill.scaffold.policy

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.policy.model.PlatformPackDefaults
import skillbill.scaffold.policy.model.PlatformPackSelection
import java.nio.file.Path

/**
 * SKILL-52.1 subtask 2: pure-policy half of platform-pack scaffolding.
 *
 * Resolves the wire-payload selection (`specialist_areas` / `skeleton_mode`), derives the
 * routing-signal + display-name defaults, composes the human-readable notes that ship with a
 * scaffold result, and produces the deterministic install-path list.
 *
 * Uses `java.nio.file.Path` only for path arithmetic (`resolve`) — there is no IO. `PlatformPackSelection`
 * and `PlatformPackDefaults` live in `skillbill.scaffold.policy.model` per the domain `model` rule.
 */

/**
 * Parses `specialist_areas` and `skeleton_mode` from [payload] and resolves the canonical sorted
 * selection. Loud-fails when areas are unknown, when `skeleton_mode` is out of range, or when
 * both fields are provided simultaneously.
 */
fun resolvePlatformPackSelection(payload: Map<String, Any?>): PlatformPackSelection {
  val skeletonMode = requireStringOrDefault(payload, "skeleton_mode", "full")
  val specialistAreas = payload["specialist_areas"]?.let { value ->
    val areas = requireStringList(value, "specialist_areas")
    val unknown = areas.filter { area -> area !in APPROVED_CODE_REVIEW_AREAS }
    if (unknown.isNotEmpty()) {
      failPlatformPackPolicy(
        "Scaffold payload field 'specialist_areas' contains unknown areas $unknown; " +
          "approved areas: $APPROVED_CODE_REVIEW_AREAS.",
      )
    }
    areas
  }
  if (skeletonMode !in setOf("starter", "full")) {
    failPlatformPackPolicy(
      "Scaffold payload field 'skeleton_mode' must be one of [full, starter] when provided.",
    )
  }
  if (specialistAreas != null && payload.containsKey("skeleton_mode")) {
    failPlatformPackPolicy(
      "Scaffold payload may not provide both 'skeleton_mode' and 'specialist_areas'; " +
        "choose one specialist selection mode.",
    )
  }
  val selectedAreas =
    specialistAreas?.let { requested ->
      APPROVED_CODE_REVIEW_AREAS.sorted().filter { area -> area in requested.toSet() }
    }
      ?: if (skeletonMode == "full") APPROVED_CODE_REVIEW_AREAS.sorted() else emptyList()
  return PlatformPackSelection(selectedAreas = selectedAreas)
}

private fun failPlatformPackPolicy(message: String): Nothing = throw InvalidScaffoldPayloadError(message)

/**
 * Composes the human-readable note list shipped with a scaffold result. [presetUsed] indicates
 * that built-in defaults were applied; the area-count phrasing depends on whether code-review
 * specialists were selected.
 */
fun platformPackNotes(platform: String, presetUsed: Boolean, selectedAreas: List<String>): List<String> {
  val notes = mutableListOf<String>()
  if (presetUsed) {
    notes +=
      "Applied built-in platform preset for '$platform'. " +
      "Override 'routing_signals' only when the defaults need adjustment."
  }
  notes += if (selectedAreas.isNotEmpty()) {
    "Full skeleton scaffolded with ${selectedAreas.size} approved code-review area stubs."
  } else {
    "Quality-check scaffolded by default."
  }
  notes += "Follow-on code-review-area scaffolds can extend the pack without manual manifest edits."
  notes += sharedContractNote()
  return notes
}

/**
 * Merges payload `display_name` and `routing_signals.*` overrides with the built-in preset
 * (if any) and returns the canonical platform-pack defaults. Loud-fails when no strong routing
 * signals are resolvable and no preset is available.
 */
fun resolvePlatformPackDefaults(payload: Map<String, Any?>, platform: String): PlatformPackDefaults {
  val preset = PLATFORM_PACK_PRESET_DESCRIPTORS[platform]
  val routing = payload["routing_signals"] as? Map<*, *>
  val strong = routing?.get("strong")?.let { requireStringList(it, "routing_signals.strong") }
  val tieBreakers = routing?.get("tie_breakers")?.let { requireStringList(it, "routing_signals.tie_breakers") }
  val resolvedStrong = strong ?: preset?.strongSignals.orEmpty()
  val resolvedTieBreakers = tieBreakers ?: preset?.tieBreakers.orEmpty()
  if (resolvedStrong.isEmpty()) {
    val message = if (preset == null) {
      "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal " +
        "when no built-in platform preset exists for '$platform'."
    } else {
      "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal."
    }
    failPlatformPackPolicy(message)
  }
  val displayName = requireStringOrDefault(
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

/**
 * Builds the deterministic install-path list for a freshly scaffolded platform pack: baseline
 * shell first, then quality-check shell, then each selected specialist area.
 */
fun buildPlatformPackInstallPaths(
  packRoot: Path,
  baselineName: String,
  qualityCheckName: String,
  specialistPaths: Map<String, Path>,
  selectedAreas: List<String>,
): List<Path> = buildList {
  add(packRoot.resolve("code-review").resolve(baselineName))
  add(packRoot.resolve("quality-check").resolve(qualityCheckName))
  selectedAreas.forEach { area ->
    add(specialistPaths.getValue(area))
  }
}
