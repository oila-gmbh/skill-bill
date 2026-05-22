@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import java.nio.file.Path

private val AREAS_EMPTY_INLINE_PATTERN =
  Regex("^declared_code_review_areas:\\s*\\[\\s*\\]\\s*$", RegexOption.MULTILINE)
private val AREAS_LIST_PATTERN =
  Regex("^declared_code_review_areas:\\s*\\n((?:[ \\t]+-[^\\n]*\\n)*)", RegexOption.MULTILINE)
private val DECLARED_FILES_EMPTY_INLINE_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*?))(  areas:\\s*\\{\\s*\\}\\s*)", RegexOption.MULTILINE)
private val AREAS_FILES_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*?))(  areas:\\n)((?:    [^\\n]+\\n)*)", RegexOption.MULTILINE)
private val AREA_METADATA_EMPTY_INLINE_PATTERN =
  Regex("^area_metadata:\\s*\\{\\s*\\}\\s*$", RegexOption.MULTILINE)
private val AREA_METADATA_BLOCK_PATTERN =
  Regex("^(area_metadata:\\n)((?:  [^\\n]+\\n|    [^\\n]+\\n)*)", RegexOption.MULTILINE)
private val QUALITY_CHECK_KEY_PATTERN =
  Regex("^declared_quality_check_file:\\s*(.+)$", RegexOption.MULTILINE)
private val DECLARED_FILES_BLOCK_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*))", RegexOption.MULTILINE)
private val TOP_LEVEL_KEY_PATTERN = Regex("^[^\\s#][^:\\n]*:", RegexOption.MULTILINE)

internal fun appendCodeReviewArea(manifestPath: Path, area: String, relativeContentPath: String, areaFocus: String) {
  val original = manifestPath.toFile().readText()
  var updated = original
  updated = appendAreaToList(updated, area)
  updated = appendAreaToDeclaredFiles(updated, area, relativeContentPath)
  updated = appendAreaMetadata(updated, area, areaFocus)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun setDeclaredQualityCheckFile(manifestPath: Path, relativeContentPath: String) {
  val original = manifestPath.toFile().readText()
  val updated =
    QUALITY_CHECK_KEY_PATTERN.find(original)?.let { match ->
      original.replaceRange(match.range, "declared_quality_check_file: ${yamlScalar(relativeContentPath)}")
    } ?: run {
      val blockMatch = DECLARED_FILES_BLOCK_PATTERN.find(original)
        ?: throw InvalidScaffoldPayloadError(
          "Manifest is missing 'declared_files:' block; refusing to append declared_quality_check_file.",
        )
      val insertion = "\ndeclared_quality_check_file: ${yamlScalar(relativeContentPath)}\n"
      original.replaceRange(blockMatch.range.last + 1, blockMatch.range.last + 1, insertion)
    }
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun appendGovernedAddonManifestRegistration(
  manifestPath: Path,
  platform: String,
  skillRelativeDirs: List<String>,
  addonSlug: String,
) {
  val original = manifestPath.toFile().readText()
  val updated = renderGovernedAddonManifestRegistration(original, platform, skillRelativeDirs, addonSlug)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun renderGovernedAddonManifestRegistration(
  text: String,
  platform: String,
  skillRelativeDirs: List<String>,
  addonSlug: String,
): String {
  val pointerName = "$addonSlug.md"
  val target = "platform-packs/$platform/addons/$pointerName"
  return skillRelativeDirs.fold(text) { current, skillRelativeDir ->
    val withPointer = appendManifestPointer(current, skillRelativeDir, pointerName, target)
    appendAddonUsage(withPointer, skillRelativeDir, addonSlug, pointerName)
  }
}

internal fun renderPlatformPackManifest(
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
  lines += "contract_version: ${yamlScalar(SHELL_CONTRACT_VERSION)}"
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

private fun appendManifestPointer(text: String, skillRelativeDir: String, pointerName: String, target: String): String =
  appendPointerLikeEntry(
    text = text,
    blockName = "pointers",
    skillRelativeDir = skillRelativeDir,
    entryName = pointerName,
    renderBlock = {
      "  $skillRelativeDir:\n" +
        "    - name: ${yamlScalar(pointerName)}\n" +
        "      target: ${yamlScalar(target)}\n"
    },
    renderEntry = {
      "    - name: ${yamlScalar(pointerName)}\n" +
        "      target: ${yamlScalar(target)}\n"
    },
    existingEntryPattern = Regex(
      "^    - name:\\s*['\"]?${Regex.escape(pointerName)}['\"]?\\s*$",
      RegexOption.MULTILINE,
    ),
  )

private fun appendAddonUsage(text: String, skillRelativeDir: String, addonSlug: String, pointerName: String): String =
  appendPointerLikeEntry(
    text = text,
    blockName = "addon_usage",
    skillRelativeDir = skillRelativeDir,
    entryName = addonSlug,
    renderBlock = {
      "  $skillRelativeDir:\n" +
        "    - slug: ${yamlScalar(addonSlug)}\n" +
        "      entrypoint: ${yamlScalar(pointerName)}\n"
    },
    renderEntry = {
      "    - slug: ${yamlScalar(addonSlug)}\n" +
        "      entrypoint: ${yamlScalar(pointerName)}\n"
    },
    existingEntryPattern = Regex("^    - slug:\\s*['\"]?${Regex.escape(addonSlug)}['\"]?\\s*$", RegexOption.MULTILINE),
  )

private fun appendPointerLikeEntry(
  text: String,
  blockName: String,
  skillRelativeDir: String,
  @Suppress("UNUSED_PARAMETER") entryName: String,
  renderBlock: () -> String,
  renderEntry: () -> String,
  existingEntryPattern: Regex,
): String {
  val blockRange = topLevelBlockRange(text, blockName)
    ?: return text.trimEnd() + "\n\n$blockName:\n${renderBlock()}"
  val skillRange = nestedSkillDirRange(text, blockRange, skillRelativeDir)
  if (skillRange == null) {
    return text.replaceRange(blockRange.last + 1, blockRange.last + 1, renderBlock())
  }
  val existingBlock = text.substring(skillRange)
  if (existingEntryPattern.containsMatchIn(existingBlock)) {
    return text
  }
  return text.replaceRange(skillRange.last + 1, skillRange.last + 1, renderEntry())
}

private fun topLevelBlockRange(text: String, blockName: String): IntRange? {
  val header = Regex("^${Regex.escape(blockName)}:\\s*$", RegexOption.MULTILINE).find(text) ?: return null
  val next = TOP_LEVEL_KEY_PATTERN.find(text, startIndex = header.range.last + 1)
  val endExclusive = next?.range?.first ?: text.length
  return header.range.first until endExclusive
}

private fun nestedSkillDirRange(text: String, blockRange: IntRange, skillRelativeDir: String): IntRange? {
  val block = text.substring(blockRange)
  val localHeader = Regex(
    "^  ${Regex.escape(skillRelativeDir)}:\\s*$",
    RegexOption.MULTILINE,
  ).find(block) ?: return null
  val start = blockRange.first + localHeader.range.first
  val next = Regex("^  [^\\s].*:\\s*$", RegexOption.MULTILINE)
    .find(text, startIndex = start + localHeader.value.length)
  val endExclusive = next?.range?.first?.takeIf { it <= blockRange.last + 1 } ?: (blockRange.last + 1)
  return start until endExclusive
}

private fun appendAreaToList(text: String, area: String): String {
  if (AREAS_EMPTY_INLINE_PATTERN.containsMatchIn(text)) {
    return text.replace(
      AREAS_EMPTY_INLINE_PATTERN,
      "declared_code_review_areas:\n  - ${yamlScalar(area)}",
    )
  }
  val match = AREAS_LIST_PATTERN.find(text)
    ?: throw InvalidScaffoldPayloadError(
      "Manifest is missing required 'declared_code_review_areas:' block; refusing to edit.",
    )
  val body = match.groupValues[1]
  if (Regex("^[ \\t]+-\\s*(?:\"|')?${Regex.escape(area)}(?:\"|')?\\s*$", RegexOption.MULTILINE).containsMatchIn(body)) {
    return text
  }
  val indent = detectListIndent(body).ifBlank { "  " }
  val insertion = "$indent- ${yamlScalar(area)}\n"
  return text.replaceRange(match.range, "declared_code_review_areas:\n$body$insertion")
}

private fun appendAreaToDeclaredFiles(text: String, area: String, relativePath: String): String {
  if (DECLARED_FILES_EMPTY_INLINE_PATTERN.containsMatchIn(text)) {
    val match = DECLARED_FILES_EMPTY_INLINE_PATTERN.find(text)
      ?: return text
    val prefix = match.groupValues[1]
    return text.replaceRange(match.range, prefix + "  areas:\n    $area: ${yamlScalar(relativePath)}\n")
  }
  val match = AREAS_FILES_PATTERN.find(text)
    ?: throw InvalidScaffoldPayloadError("Manifest is missing 'declared_files.areas:' block; refusing to edit.")
  val prefix = match.groupValues[1]
  val header = match.groupValues[2]
  val body = match.groupValues[3]
  if (Regex("^    ${Regex.escape(area)}:\\s", RegexOption.MULTILINE).containsMatchIn(body)) {
    return text
  }
  val insertion = "    $area: ${yamlScalar(relativePath)}\n"
  return text.replaceRange(match.range, prefix + header + body + insertion)
}

private fun appendAreaMetadata(text: String, area: String, areaFocus: String): String {
  if (Regex("^  ${Regex.escape(area)}:\\s*$", RegexOption.MULTILINE).containsMatchIn(text)) {
    return text
  }
  if (AREA_METADATA_EMPTY_INLINE_PATTERN.containsMatchIn(text)) {
    return text.replace(
      AREA_METADATA_EMPTY_INLINE_PATTERN,
      "area_metadata:\n  $area:\n    focus: ${yamlScalar(areaFocus)}",
    )
  }
  val match = AREA_METADATA_BLOCK_PATTERN.find(text)
    ?: throw InvalidScaffoldPayloadError("Manifest is missing 'area_metadata:' block; refusing to edit.")
  val header = match.groupValues[1]
  val body = match.groupValues[2]
  val insertion = "  $area:\n    focus: ${yamlScalar(areaFocus)}\n"
  return text.replaceRange(match.range, header + body + insertion)
}

private fun detectListIndent(listBody: String): String =
  listBody.lineSequence().firstOrNull { it.trimStart().startsWith("- ") }?.let { line ->
    line.substring(0, line.indexOf('-'))
  }.orEmpty()

private fun yamlScalar(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

/**
 * Inverse of [appendCodeReviewArea]. Removes the area entry from `declared_code_review_areas`,
 * `declared_files.areas`, and the matching `area_metadata.<area>` block. Idempotent — if the
 * area is not present anywhere, the file is left untouched (no write).
 *
 * Mirrors the regex anchors of the append helpers so the round-trip is structural, not textual.
 */
internal fun removeCodeReviewArea(manifestPath: Path, area: String) {
  val original = manifestPath.toFile().readText()
  var updated = original
  updated = removeAreaFromList(updated, area)
  updated = removeAreaFromDeclaredFiles(updated, area)
  updated = removeAreaMetadata(updated, area)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

/**
 * Strips the `  baseline:` line under `declared_files:`. Used when a horizontal-skill removal
 * deletes the baseline content directory and the manifest must stop pointing at it. Idempotent:
 * if no baseline line is present, the file is left untouched.
 */
internal fun removeDeclaredFilesBaseline(manifestPath: Path) {
  val original = manifestPath.toFile().readText()
  val pattern = Regex(
    "^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*?))(  baseline:[^\\n]*\\n)",
    RegexOption.MULTILINE,
  )
  val match = pattern.find(original) ?: return
  val before = match.groupValues[1]
  val updated = original.replaceRange(match.range, before)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

/**
 * Removes a single top-level mapping entry from the `pointers:` block, identified by the dotted
 * key (e.g. `code-review/bill-kmp-code-review-ui`). The key line and every subsequent line at
 * indent >= 4 (the YAML-block children of that key) are dropped together. If the removal leaves
 * the `pointers:` block with no children, the block header is collapsed to `pointers: {}` so the
 * manifest remains valid YAML. Idempotent: missing keys are a no-op.
 */
internal fun removePointersBlockKey(manifestPath: Path, key: String) {
  val original = manifestPath.toFile().readText()
  val lines = original.split('\n')
  val keyHeaderPrefix = "  $key:"
  val keyIdx = lines.indexOfFirst { line ->
    line == keyHeaderPrefix || line.startsWith("$keyHeaderPrefix ") || line.startsWith("$keyHeaderPrefix\t")
  }
  if (keyIdx < 0) return
  val endIdx = lines.asSequence()
    .drop(keyIdx + 1)
    .indexOfFirst { line -> line.isNotBlank() && line.takeWhile { ch -> ch == ' ' }.length < 4 }
    .let { offset -> if (offset < 0) lines.size else keyIdx + 1 + offset }
  val stripped = (lines.subList(0, keyIdx) + lines.subList(endIdx, lines.size)).joinToString("\n")
  val updated = collapseEmptyPointersBlock(stripped)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

private fun collapseEmptyPointersBlock(text: String): String {
  val lines = text.split('\n')
  val pointersIdx = lines.indexOfFirst { it == "pointers:" }
  if (pointersIdx < 0) return text
  val hasChildren = lines.drop(pointersIdx + 1).any { line ->
    line.isNotBlank() && line.startsWith("  ")
  }
  if (hasChildren) return text
  val updated = lines.toMutableList()
  updated[pointersIdx] = "pointers: {}"
  return updated.joinToString("\n")
}

/**
 * Inverse of [setDeclaredQualityCheckFile]. Strips the `declared_quality_check_file:` line entirely.
 * Idempotent: when the key is absent the file is not rewritten.
 *
 * Also collapses the leading blank line that [setDeclaredQualityCheckFile] inserts when the key
 * was first written, so the manifest stays clean after removal.
 */
internal fun removeDeclaredQualityCheckFile(manifestPath: Path) {
  val original = manifestPath.toFile().readText()
  val match = QUALITY_CHECK_KEY_PATTERN.find(original) ?: return
  // Capture the preceding blank line (if any) so we restore the file to its pre-set shape.
  val lineStart = match.range.first
  val precedingBlankStart = if (lineStart >= 2 && original[lineStart - 1] == '\n' && original[lineStart - 2] == '\n') {
    lineStart - 1
  } else {
    lineStart
  }
  val lineEnd = match.range.last + 1
  // Drop the trailing newline if there is one (`replaceRange` is exclusive on `endIndex`).
  val cutEnd = if (lineEnd < original.length && original[lineEnd] == '\n') lineEnd + 1 else lineEnd
  val updated = original.substring(0, precedingBlankStart) + original.substring(cutEnd.coerceAtMost(original.length))
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

private fun removeAreaFromList(text: String, area: String): String {
  val match = AREAS_LIST_PATTERN.find(text) ?: return text
  val body = match.groupValues[1]
  val areaLinePattern = Regex(
    "^[ \\t]+-\\s*(?:\"|')?${Regex.escape(area)}(?:\"|')?\\s*\\n",
    RegexOption.MULTILINE,
  )
  val newBody = areaLinePattern.replace(body, "")
  if (newBody == body) {
    return text
  }
  if (newBody.isBlank()) {
    // Collapsing the last entry → restore inline empty form so the manifest stays valid YAML.
    return text.replaceRange(match.range, "declared_code_review_areas: []\n")
  }
  return text.replaceRange(match.range, "declared_code_review_areas:\n$newBody")
}

private fun removeAreaFromDeclaredFiles(text: String, area: String): String {
  val match = AREAS_FILES_PATTERN.find(text) ?: return text
  val prefix = match.groupValues[1]
  val header = match.groupValues[2]
  val body = match.groupValues[3]
  val areaEntryPattern = Regex("^    ${Regex.escape(area)}:[^\\n]*\\n", RegexOption.MULTILINE)
  val newBody = areaEntryPattern.replace(body, "")
  if (newBody == body) {
    return text
  }
  if (newBody.isBlank()) {
    return text.replaceRange(match.range, prefix + "  areas: {}\n")
  }
  return text.replaceRange(match.range, prefix + header + newBody)
}

private fun removeAreaMetadata(text: String, area: String): String {
  val match = AREA_METADATA_BLOCK_PATTERN.find(text) ?: return text
  val header = match.groupValues[1]
  val body = match.groupValues[2]
  // An area metadata entry looks like:
  //   <area>:
  //     focus: ...
  // Strip both lines (the heading line and any nested children indented deeper than the header).
  val entryPattern = Regex(
    "^  ${Regex.escape(area)}:\\s*\\n(?:    [^\\n]*\\n)*",
    RegexOption.MULTILINE,
  )
  val newBody = entryPattern.replace(body, "")
  if (newBody == body) {
    return text
  }
  if (newBody.isBlank()) {
    return text.replaceRange(match.range, "area_metadata: {}\n")
  }
  return text.replaceRange(match.range, header + newBody)
}
