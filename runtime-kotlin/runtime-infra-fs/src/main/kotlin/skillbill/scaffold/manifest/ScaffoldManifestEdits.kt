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

// SKILL-52.1 subtask 2: `renderPlatformPackManifest` and its YAML-emit helpers now live in
// `skillbill.scaffold.policy.PlatformPackManifestPolicy` (runtime-domain). The only remaining
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

/**
 * Removes every platform-pack manifest reference to an add-on pointer filename.
 *
 * This handles both generated pointer declarations and governed add-on usage:
 * - `pointers.<skill-dir>[]` entries whose `name` matches [pointerName]
 * - `addon_usage.<skill-dir>[]` entries whose `entrypoint` matches [pointerName]
 * - `companion_pointers` list values matching [pointerName]
 */
internal fun removeAddonReferences(manifestPath: Path, pointerName: String) {
  val original = manifestPath.toFile().readText()
  var updated = removeNamedPointerEntries(original, blockName = "pointers", pointerName = pointerName)
  updated = removeAddonUsageEntries(updated, pointerName)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

/** Removes one pointer slug from an orchestration skill-class manifest's `pointers:` list. */
internal fun removeSkillClassPointer(manifestPath: Path, pointerSlug: String) {
  val original = manifestPath.toFile().readText()
  val lines = original.split('\n').toMutableList()
  val block = topLevelBlockLineRange(lines, "pointers") ?: return
  val removeIdx = (block.first + 1..block.last).firstOrNull { idx ->
    val line = lines.getOrNull(idx) ?: return@firstOrNull false
    leadingSpaces(line) == 2 && yamlListScalar(line) == pointerSlug
  } ?: return
  lines.removeAt(removeIdx)
  val updated = lines.joinToString("\n")
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

private fun removeNamedPointerEntries(text: String, blockName: String, pointerName: String): String {
  val lines = text.split('\n').toMutableList()
  var changed = false
  var block = topLevelBlockLineRange(lines, blockName) ?: return text
  var idx = block.first + 1
  while (idx <= block.last && idx < lines.size) {
    val line = lines[idx]
    if (leadingSpaces(line) == 4 && namedListEntryValue(line, "name") == pointerName) {
      val end = listItemEnd(lines, idx, maxExclusive = block.last + 1)
      repeat(end - idx) { lines.removeAt(idx) }
      changed = true
      block = topLevelBlockLineRange(lines, blockName) ?: return lines.joinToString("\n")
      continue
    }
    idx += 1
  }
  if (changed) {
    collapseEmptyNestedMappings(lines, blockName)
  }
  return if (changed) lines.joinToString("\n") else text
}

private fun removeAddonUsageEntries(text: String, pointerName: String): String {
  val lines = text.split('\n').toMutableList()
  var changed = removeAddonUsageEntrypointItems(lines, pointerName)
  changed = removeAddonUsageCompanionPointers(lines, pointerName) || changed
  if (changed) {
    collapseEmptyCompanionPointerBlocks(lines)
    collapseEmptyNestedMappings(lines, "addon_usage")
  }
  return if (changed) lines.joinToString("\n") else text
}

private fun removeAddonUsageEntrypointItems(lines: MutableList<String>, pointerName: String): Boolean {
  var changed = false
  var block = topLevelBlockLineRange(lines, "addon_usage") ?: return false
  var idx = block.first + 1
  while (idx <= block.last && idx < lines.size) {
    val removesItem = isAddonUsageItemStart(lines[idx]) &&
      listItemContainsEntrypoint(lines, idx, block.last + 1, pointerName)
    if (removesItem) {
      val end = listItemEnd(lines, idx, maxExclusive = block.last + 1)
      repeat(end - idx) { lines.removeAt(idx) }
      changed = true
      block = topLevelBlockLineRange(lines, "addon_usage") ?: return true
    } else {
      idx += 1
    }
  }
  return changed
}

private fun removeAddonUsageCompanionPointers(lines: MutableList<String>, pointerName: String): Boolean {
  var changed = false
  var block = topLevelBlockLineRange(lines, "addon_usage") ?: return false
  var idx = block.first + 1
  while (idx <= block.last && idx < lines.size) {
    if (leadingSpaces(lines[idx]) == COMPANION_POINTER_ITEM_INDENT && yamlListScalar(lines[idx]) == pointerName) {
      lines.removeAt(idx)
      changed = true
      block = topLevelBlockLineRange(lines, "addon_usage") ?: return true
    } else {
      idx += 1
    }
  }
  return changed
}

private fun isAddonUsageItemStart(line: String): Boolean =
  leadingSpaces(line) == NESTED_LIST_ITEM_INDENT && line.trimStart().startsWith("- slug:")

private fun listItemContainsEntrypoint(
  lines: List<String>,
  start: Int,
  maxExclusive: Int,
  pointerName: String,
): Boolean {
  val end = listItemEnd(lines, start, maxExclusive)
  return lines.subList(start, end).any { line -> keyValue(line, "entrypoint") == pointerName }
}

private fun topLevelBlockLineRange(lines: List<String>, blockName: String): IntRange? {
  val start = lines.indexOfFirst { line -> line == "$blockName:" || line == "$blockName: {}" }
  if (start < 0 || lines[start] == "$blockName: {}") return null
  val next = lines.asSequence()
    .drop(start + 1)
    .indexOfFirst { line -> line.isNotBlank() && leadingSpaces(line) == 0 && line.contains(':') }
    .let { offset -> if (offset < 0) lines.size else start + 1 + offset }
  return start until next
}

private fun listItemEnd(lines: List<String>, start: Int, maxExclusive: Int): Int {
  var idx = start + 1
  while (idx < maxExclusive && idx < lines.size) {
    val line = lines[idx]
    if (line.isNotBlank() && leadingSpaces(line) <= leadingSpaces(lines[start])) break
    idx += 1
  }
  return idx
}

private fun collapseEmptyCompanionPointerBlocks(lines: MutableList<String>) {
  var idx = 0
  while (idx < lines.size) {
    if (leadingSpaces(lines[idx]) == COMPANION_POINTER_HEADER_INDENT && lines[idx].trim() == "companion_pointers:") {
      val next = idx + 1
      val hasCompanions = next < lines.size &&
        leadingSpaces(lines[next]) == COMPANION_POINTER_ITEM_INDENT &&
        lines[next].trimStart().startsWith("- ")
      if (!hasCompanions) {
        lines.removeAt(idx)
        continue
      }
    }
    idx += 1
  }
}

private fun collapseEmptyNestedMappings(lines: MutableList<String>, blockName: String) {
  removeEmptyNestedMappingBlocks(lines, blockName)
  collapseTopLevelMappingIfEmpty(lines, blockName)
}

private fun removeEmptyNestedMappingBlocks(lines: MutableList<String>, blockName: String) {
  var block = topLevelBlockLineRange(lines, blockName) ?: return
  var idx = block.first + 1
  while (idx <= block.last && idx < lines.size) {
    val end = nestedMappingEnd(lines, idx, block.last + 1)
    if (isNestedMappingHeader(lines[idx]) && !nestedMappingHasListItem(lines, idx, end)) {
      lines.subList(idx, end.coerceAtMost(lines.size)).clear()
      block = topLevelBlockLineRange(lines, blockName) ?: return
      continue
    }
    idx += 1
  }
}

private fun collapseTopLevelMappingIfEmpty(lines: MutableList<String>, blockName: String) {
  val block = topLevelBlockLineRange(lines, blockName) ?: return
  val hasNestedMappings = lines.subList(block.first + 1, (block.last + 1).coerceAtMost(lines.size)).any { line ->
    line.isNotBlank() && leadingSpaces(line) == NESTED_MAPPING_INDENT
  }
  if (!hasNestedMappings) {
    lines[block.first] = "$blockName: {}"
  }
}

private fun isNestedMappingHeader(line: String): Boolean =
  leadingSpaces(line) == NESTED_MAPPING_INDENT && line.trimEnd().endsWith(":")

private fun nestedMappingEnd(lines: List<String>, start: Int, maxExclusive: Int): Int = lines.asSequence()
  .drop(start + 1)
  .take(maxExclusive - start - 1)
  .indexOfFirst { line -> line.isNotBlank() && leadingSpaces(line) <= NESTED_MAPPING_INDENT }
  .let { offset -> if (offset < 0) maxExclusive else start + 1 + offset }

private fun nestedMappingHasListItem(lines: List<String>, start: Int, end: Int): Boolean =
  lines.subList(start + 1, end.coerceAtMost(lines.size)).any { line ->
    line.isNotBlank() && leadingSpaces(line) == NESTED_LIST_ITEM_INDENT && line.trimStart().startsWith("- ")
  }

private fun namedListEntryValue(line: String, key: String): String? {
  val trimmed = line.trim()
  val prefix = "- $key:"
  return if (trimmed.startsWith(prefix)) unquoteYamlScalar(trimmed.removePrefix(prefix).trim()) else null
}

private fun keyValue(line: String, key: String): String? {
  val trimmed = line.trim()
  val prefix = "$key:"
  return if (trimmed.startsWith(prefix)) unquoteYamlScalar(trimmed.removePrefix(prefix).trim()) else null
}

private fun yamlListScalar(line: String): String? {
  val trimmed = line.trim()
  return if (trimmed.startsWith("- ")) unquoteYamlScalar(trimmed.removePrefix("- ").trim()) else null
}

private fun unquoteYamlScalar(value: String): String = value.removeSurrounding("\"").removeSurrounding("'")

private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.length

private const val NESTED_MAPPING_INDENT = 2
private const val NESTED_LIST_ITEM_INDENT = 4
private const val COMPANION_POINTER_HEADER_INDENT = 6
private const val COMPANION_POINTER_ITEM_INDENT = 8

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
