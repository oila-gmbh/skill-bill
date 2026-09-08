package skillbill.scaffold.policy.platformpack

import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestContentRenderRequest
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestRenderRequest

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
const val PLATFORM_PACK_SHELL_CONTRACT_VERSION: String = "1.7"

/**
 * Renders the canonical `platform.yaml` text for a freshly scaffolded platform pack. All path
 * arguments must already be absolute or pack-root-relative; this function does no IO and never
 * reads from disk.
 */
fun renderPlatformPackManifest(request: PlatformPackManifestRenderRequest): String {
  val lines = mutableListOf<String>()
  lines += "platform: ${yamlScalar(request.platform)}"
  lines += "contract_version: ${yamlScalar(PLATFORM_PACK_SHELL_CONTRACT_VERSION)}"
  lines += "display_name: ${yamlScalar(request.displayName)}"
  lines += ""
  appendRoutingSignals(lines, request.strongSignals, request.tieBreakers)
  lines += ""
  appendDeclaredCodeReviewAreas(lines, request.declaredCodeReviewAreas)
  lines += ""
  appendDeclaredFiles(lines, request.baselineContentPath, request.declaredCodeReviewAreas, request.declaredAreaFiles)
  appendAreaMetadata(lines, request.declaredCodeReviewAreas, request.areaMetadata)
  appendQualityCheckDeclaration(lines, request.declaredQualityCheckFile)
  appendPointers(
    lines,
    request.baselineContentPath,
    request.declaredCodeReviewAreas,
    request.declaredAreaFiles,
    request.declaredQualityCheckFile,
  )
  appendBaselineLayers(lines, request.baselineLayers)
  if (request.notes != null) {
    lines += ""
    lines += "notes: ${yamlScalar(request.notes)}"
  }
  return lines.joinToString("\n") + "\n"
}

fun renderPlatformPackManifestContent(request: PlatformPackManifestContentRenderRequest): String =
  renderPlatformPackManifest(
    PlatformPackManifestRenderRequest(
      platform = request.platform,
      displayName = request.displayName,
      strongSignals = request.routingSignals,
      tieBreakers = request.tieBreakers,
      declaredCodeReviewAreas = request.specialistAreas,
      baselineContentPath = request.packRoot.relativize(request.baselineSkillPath.resolve("content.md"))
        .toString()
        .replace('\\', '/'),
      declaredAreaFiles = request.specialistSkillPaths.mapValues { (_, path) ->
        request.packRoot.relativize(path.resolve("content.md")).toString().replace('\\', '/')
      },
      declaredQualityCheckFile = request.packRoot.relativize(request.qualityCheckSkillPath.resolve("content.md"))
        .toString()
        .replace('\\', '/'),
      areaMetadata = request.specialistAreaMetadata,
      baselineLayers = request.baselineLayers,
    ),
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
