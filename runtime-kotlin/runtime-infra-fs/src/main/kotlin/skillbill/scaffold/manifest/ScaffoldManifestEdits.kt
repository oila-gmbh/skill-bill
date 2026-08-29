@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package skillbill.scaffold.manifest

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.runtime.scaffold
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

internal fun appendExternalAddonManifestRegistration(
  manifestPath: Path,
  skillRelativeDirs: List<String>,
  addonSlug: String,
) {
  val original = manifestPath.toFile().readText()
  val updated = renderExternalAddonManifestRegistration(original, skillRelativeDirs, addonSlug)
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

internal fun renderExternalAddonManifestRegistration(
  text: String,
  skillRelativeDirs: List<String>,
  addonSlug: String,
): String {
  val pointerName = "$addonSlug.md"
  return skillRelativeDirs.fold(text) { current, skillRelativeDir ->
    val withPointer = appendManifestPointer(current, skillRelativeDir, pointerName, pointerName)
    appendAddonUsage(withPointer, skillRelativeDir, addonSlug, pointerName)
  }
}

// SKILL-52.1 subtask 2: `renderPlatformPackManifest` and its YAML-emit helpers now live in
// `skillbill.scaffold.policy.platformpack.PlatformPackManifestPolicy` (runtime-domain). The only remaining
// platform-pack manifest concerns in this file are the on-disk text mutators
// (`appendCodeReviewArea`, `setDeclaredQualityCheckFile`, governed-addon registration etc.)
// which keep their YAML-text helpers below because they edit existing files rather than
// emitting fresh content.

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

private val README_CATALOG_ROW_PATTERN =
  Regex("""^\| `/(bill-[a-z0-9-]+)` \|[^\n]*$""", RegexOption.MULTILINE)

/**
 * Inserts a row into the README's slash-command catalog table for [skillName], placed in
 * alphabetical order against the other `bill-*` rows. Idempotent — if [skillName] already has a
 * row, the file is left untouched. Throws [InvalidScaffoldPayloadError] when no catalog table is
 * present (the README is malformed enough that scaffolding should not silently proceed).
 */
internal fun appendReadmeCatalogRow(readmePath: Path, skillName: String, description: String) {
  val original = readmePath.toFile().readText()
  val updated = renderReadmeCatalogRow(original, skillName, description)
  if (updated != original) {
    readmePath.toFile().writeText(updated)
  }
}

internal fun renderReadmeCatalogRow(text: String, skillName: String, description: String): String {
  val rows = README_CATALOG_ROW_PATTERN.findAll(text).toList()
  if (rows.isEmpty()) {
    throw InvalidScaffoldPayloadError(
      "README.md does not contain a `/bill-*` catalog table; refusing to append a row for $skillName.",
    )
  }
  if (rows.any { match -> match.groupValues[1] == skillName }) {
    return text
  }
  val safeDescription = sanitizeCatalogDescription(description)
  val newRow = "| `/$skillName` | $safeDescription |"
  val insertAfter = rows
    .lastOrNull { match -> match.groupValues[1].compareTo(skillName) < 0 }
  return if (insertAfter != null) {
    val anchor = insertAfter.range.last + 1
    text.substring(0, anchor) + "\n" + newRow + text.substring(anchor)
  } else {
    val anchor = rows.first().range.first
    text.substring(0, anchor) + newRow + "\n" + text.substring(anchor)
  }
}

private fun sanitizeCatalogDescription(description: String): String {
  val collapsed = description.replace(Regex("\\s+"), " ").trim()
  val escaped = collapsed.replace("|", "\\|")
  return escaped.ifBlank { "TODO: describe this skill." }
}

/**
 * Inverse of [appendCodeReviewArea]. Removes the area entry from `declared_code_review_areas`,
 * `declared_files.areas`, and the matching `area_metadata.<area>` block. Idempotent — if the
 * area is not present anywhere, the file is left untouched (no write).
 *
 * Mirrors the regex anchors of the append helpers so the round-trip is structural, not textual.
 */
