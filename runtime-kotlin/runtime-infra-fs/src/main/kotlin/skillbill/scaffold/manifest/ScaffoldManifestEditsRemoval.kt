@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package skillbill.scaffold.manifest

import skillbill.scaffold.runtime.scaffold
import java.nio.file.Path

private val AREAS_LIST_PATTERN =
  Regex("^declared_code_review_areas:\\s*\\n((?:[ \\t]+-[^\\n]*\\n)*)", RegexOption.MULTILINE)
private val AREAS_FILES_PATTERN =
  Regex("^(declared_files:\\n(?:(?:[ \\t]+[^\\n]*\\n)*?))(  areas:\\n)((?:    [^\\n]+\\n)*)", RegexOption.MULTILINE)
private val AREA_METADATA_BLOCK_PATTERN =
  Regex("^(area_metadata:\\n)((?:  [^\\n]+\\n|    [^\\n]+\\n)*)", RegexOption.MULTILINE)
private val QUALITY_CHECK_KEY_PATTERN =
  Regex("^declared_quality_check_file:\\s*(.+)$", RegexOption.MULTILINE)

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
