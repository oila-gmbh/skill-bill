package skillbill.scaffold.policy

import java.nio.file.Path

/**
 * SKILL-52.1 subtask 2: pure-policy half of platform-pack scaffolding.
 *
 * Derives routing-signal + display-name defaults, composes the human-readable notes that ship
 * with a scaffold result, and produces the deterministic install-path list.
 *
 * Uses `java.nio.file.Path` only for path arithmetic (`resolve`) — there is no IO. `PlatformPackSelection`
 * and `PlatformPackDefaults` live in `skillbill.scaffold.policy.model` per the domain `model` rule.
 */

// SKILL-52.2 subtask 2 (Task 11): `resolvePlatformPackSelection(payload)` was retired from this
// file. Its public raw-`Map<String, Any?>` signature contributed one of the 11 scaffold input
// raw-map allow-list entries. The CLI / MCP / Desktop adapters now resolve specialist-area
// selection from the typed `ScaffoldCommandRequest.PlatformPack`; the legacy filesystem
// orchestrator path inside `runtime-infra-fs` keeps a private internal copy operating on the
// raw map (see `runtime-infra-fs/.../scaffold/ScaffoldPayloadMapPolicy.kt`).

/**
 * Composes the human-readable note list shipped with a scaffold result. [presetUsed] indicates
 * that built-in defaults were applied. Platform-pack scaffolds now always include the full
 * approved specialist set; unwanted focus areas can be removed later through governed removal.
 */
fun platformPackNotes(platform: String, presetUsed: Boolean, selectedAreas: List<String>): List<String> {
  val notes = mutableListOf<String>()
  if (presetUsed) {
    notes +=
      "Applied built-in platform preset for '$platform'. " +
      "Override 'routing_signals' only when the defaults need adjustment."
  }
  notes += "Full platform pack scaffolded with ${selectedAreas.size} approved code-review area stubs."
  notes += "Edit the generated pack content or remove unused stubs instead of creating partial follow-on scaffolds."
  notes += sharedContractNote()
  return notes
}

// SKILL-52.2 subtask 2 (Task 11): `resolvePlatformPackDefaults(payload, platform)` was retired
// from this file. Its public raw-`Map<String, Any?>` signature contributed one of the 11 scaffold
// input raw-map allow-list entries. The legacy filesystem orchestrator path inside
// `runtime-infra-fs` keeps a private internal copy operating on the raw map
// (see `runtime-infra-fs/.../scaffold/ScaffoldPayloadMapPolicy.kt`).

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
