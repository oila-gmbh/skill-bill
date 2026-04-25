@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "ReturnCount")

package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
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
  notes: String? = null,
): String {
  val lines = mutableListOf<String>()
  lines += "platform: ${yamlScalar(platform)}"
  lines += "contract_version: ${yamlScalar(SHELL_CONTRACT_VERSION)}"
  lines += "display_name: ${yamlScalar(displayName)}"
  lines += ""
  lines += "routing_signals:"
  lines += "  strong:"
  strongSignals.forEach { lines += "    - ${yamlScalar(it)}" }
  if (tieBreakers.isEmpty()) {
    lines += "  tie_breakers: []"
  } else {
    lines += "  tie_breakers:"
    tieBreakers.forEach { lines += "    - ${yamlScalar(it)}" }
  }
  lines += ""
  if (declaredCodeReviewAreas.isEmpty()) {
    lines += "declared_code_review_areas: []"
  } else {
    lines += "declared_code_review_areas:"
    declaredCodeReviewAreas.forEach { lines += "  - ${yamlScalar(it)}" }
  }
  lines += ""
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
  if (declaredQualityCheckFile != null) {
    lines += ""
    lines += "declared_quality_check_file: ${yamlScalar(declaredQualityCheckFile)}"
  }
  if (notes != null) {
    lines += ""
    lines += "notes: ${yamlScalar(notes)}"
  }
  return lines.joinToString("\n") + "\n"
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
