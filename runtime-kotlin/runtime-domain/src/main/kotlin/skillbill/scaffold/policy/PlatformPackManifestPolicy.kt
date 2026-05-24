package skillbill.scaffold.policy

import skillbill.scaffold.model.CodeReviewBaselineLayer
import java.nio.file.Path

/**
 * SKILL-52.1 subtask 2: pure-policy YAML renderer for platform-pack manifests.
 *
 * Owns the canonical `platform.yaml` content rendering used during a fresh platform-pack
 * scaffold. Implementation is pure string templating + path arithmetic (`Path.relativize` is
 * allowed in `runtime-domain`). The infra-fs IO seam writes the returned text to disk.
 */

/**
 * Shell-content contract version emitted in the generated manifest header.
 *
 * SKILL-52.1 subtask 2: this is the single source of truth for the shell-contract version. The
 * historical `runtime-infra-fs` `SHELL_CONTRACT_VERSION` is now a `get()` alias of this constant
 * (see `runtime-infra-fs/.../scaffold/ScaffoldSupport.kt`) so the two cannot drift.
 */
const val PLATFORM_PACK_SHELL_CONTRACT_VERSION: String = "1.1"

/**
 * Renders the canonical `platform.yaml` text for a freshly scaffolded platform pack. All path
 * arguments must already be absolute or pack-root-relative; this function does no IO and never
 * reads from disk.
 */
@Suppress("LongParameterList")
fun renderPlatformPackManifest(
  platform: String,
  displayName: String,
  strongSignals: List<String>,
  tieBreakers: List<String> = emptyList(),
  declaredCodeReviewAreas: List<String> = emptyList(),
  baselineContentPath: String,
  declaredAreaFiles: Map<String, String> = emptyMap(),
  declaredQualityCheckFile: String? = null,
  areaMetadata: Map<String, String> = emptyMap(),
  baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
  notes: String? = null,
): String {
  val lines = mutableListOf<String>()
  lines += "platform: ${yamlScalar(platform)}"
  lines += "contract_version: ${yamlScalar(PLATFORM_PACK_SHELL_CONTRACT_VERSION)}"
  lines += "display_name: ${yamlScalar(displayName)}"
  lines += ""
  appendRoutingSignals(lines, strongSignals, tieBreakers)
  lines += ""
  appendDeclaredCodeReviewAreas(lines, declaredCodeReviewAreas)
  lines += ""
  appendDeclaredFiles(lines, baselineContentPath, declaredCodeReviewAreas, declaredAreaFiles)
  appendAreaMetadata(lines, declaredCodeReviewAreas, areaMetadata)
  appendQualityCheckDeclaration(lines, declaredQualityCheckFile)
  appendBaselineLayers(lines, baselineLayers)
  if (notes != null) {
    lines += ""
    lines += "notes: ${yamlScalar(notes)}"
  }
  return lines.joinToString("\n") + "\n"
}

/**
 * Pure helper used by the infra-fs scaffold seam to translate plan + path inputs into the
 * relative-path strings the renderer expects, then invoke [renderPlatformPackManifest]. The
 * `packRoot.relativize(...)` calls are pure path arithmetic and produce stable forward-slash
 * strings on every platform.
 */
@Suppress("LongParameterList")
fun renderPlatformPackManifestContent(
  platform: String,
  displayName: String,
  routingSignals: List<String>,
  tieBreakers: List<String>,
  specialistAreas: List<String>,
  specialistAreaMetadata: Map<String, String>,
  baselineLayers: List<CodeReviewBaselineLayer>,
  packRoot: Path,
  baselineSkillPath: Path,
  qualityCheckSkillPath: Path,
  specialistSkillPaths: Map<String, Path>,
): String = renderPlatformPackManifest(
  platform = platform,
  displayName = displayName,
  strongSignals = routingSignals,
  tieBreakers = tieBreakers,
  declaredCodeReviewAreas = specialistAreas,
  baselineContentPath = packRoot.relativize(baselineSkillPath.resolve("content.md"))
    .toString()
    .replace('\\', '/'),
  declaredAreaFiles = specialistSkillPaths.mapValues { (_, path) ->
    packRoot.relativize(path.resolve("content.md")).toString().replace('\\', '/')
  },
  declaredQualityCheckFile = packRoot.relativize(qualityCheckSkillPath.resolve("content.md"))
    .toString()
    .replace('\\', '/'),
  areaMetadata = specialistAreaMetadata,
  baselineLayers = baselineLayers,
)

private fun appendRoutingSignals(lines: MutableList<String>, strongSignals: List<String>, tieBreakers: List<String>) {
  lines += "routing_signals:"
  lines += "  strong:"
  strongSignals.forEach { lines += "    - ${yamlScalar(it)}" }
  if (tieBreakers.isEmpty()) {
    lines += "  tie_breakers: []"
  } else {
    lines += "  tie_breakers:"
    tieBreakers.forEach { lines += "    - ${yamlScalar(it)}" }
  }
}

private fun appendDeclaredCodeReviewAreas(lines: MutableList<String>, declaredCodeReviewAreas: List<String>) {
  if (declaredCodeReviewAreas.isEmpty()) {
    lines += "declared_code_review_areas: []"
  } else {
    lines += "declared_code_review_areas:"
    declaredCodeReviewAreas.forEach { lines += "  - ${yamlScalar(it)}" }
  }
}

private fun appendDeclaredFiles(
  lines: MutableList<String>,
  baselineContentPath: String,
  declaredCodeReviewAreas: List<String>,
  declaredAreaFiles: Map<String, String>,
) {
  lines += "declared_files:"
  lines += "  baseline: ${yamlScalar(baselineContentPath)}"
  if (declaredAreaFiles.isEmpty()) {
    lines += "  areas: {}"
  } else {
    lines += "  areas:"
    declaredCodeReviewAreas.forEach { area ->
      declaredAreaFiles[area]?.let { lines += "    $area: ${yamlScalar(it)}" }
    }
  }
}

private fun appendAreaMetadata(
  lines: MutableList<String>,
  declaredCodeReviewAreas: List<String>,
  areaMetadata: Map<String, String>,
) {
  if (areaMetadata.isEmpty()) {
    lines += "area_metadata: {}"
  } else {
    lines += "area_metadata:"
    declaredCodeReviewAreas.forEach { area ->
      areaMetadata[area]?.let {
        lines += "  $area:"
        lines += "    focus: ${yamlScalar(it)}"
      }
    }
  }
}

private fun appendQualityCheckDeclaration(lines: MutableList<String>, declaredQualityCheckFile: String?) {
  if (declaredQualityCheckFile != null) {
    lines += ""
    lines += "declared_quality_check_file: ${yamlScalar(declaredQualityCheckFile)}"
  }
}

private fun appendBaselineLayers(lines: MutableList<String>, baselineLayers: List<CodeReviewBaselineLayer>) {
  if (baselineLayers.isEmpty()) return
  lines += ""
  lines += "code_review_composition:"
  lines += "  baseline_layers:"
  baselineLayers.forEach { layer ->
    lines += "    - platform: ${yamlScalar(layer.platform)}"
    lines += "      skill: ${yamlScalar(layer.skill)}"
    lines += "      scope: ${yamlScalar(layer.scope.wireValue)}"
    lines += "      required: ${layer.required}"
    lines += "      mode: ${yamlScalar(layer.mode.wireValue)}"
  }
}

private fun yamlScalar(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
