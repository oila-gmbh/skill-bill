
package skillbill.scaffold.manifest

import java.nio.file.Path

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
    .indexOfFirst { line -> line.isNotBlank() && line.takeWhile { ch -> ch == ' ' }.length < NESTED_LIST_ITEM_INDENT }
    .let { offset -> if (offset < 0) lines.size else keyIdx + 1 + offset }
  val stripped = (lines.subList(0, keyIdx) + lines.subList(endIdx, lines.size)).joinToString("\n")
  val updated = collapseEmptyPointersBlock(stripped)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun removeAddonReferences(manifestPath: Path, pointerName: String) {
  val original = manifestPath.toFile().readText()
  var updated = removeNamedPointerEntries(original, blockName = "pointers", pointerName = pointerName)
  updated = removeAddonUsageEntries(updated, pointerName)
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun removeSkillClassPointer(manifestPath: Path, pointerSlug: String) {
  val original = manifestPath.toFile().readText()
  val lines = original.split('\n').toMutableList()
  val block = topLevelBlockLineRange(lines, "pointers") ?: return
  val removeIdx = (block.first + 1..block.last).firstOrNull { idx ->
    val line = lines.getOrNull(idx) ?: return@firstOrNull false
    leadingSpaces(line) == NESTED_MAPPING_INDENT && yamlListScalar(line) == pointerSlug
  } ?: return
  lines.removeAt(removeIdx)
  val updated = lines.joinToString("\n")
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}

internal fun removeDeclaredQualityCheckFile(manifestPath: Path) {
  val original = manifestPath.toFile().readText()
  val match = QUALITY_CHECK_KEY_PATTERN.find(original) ?: return
  val lineStart = match.range.first
  val precedingBlankStart = if (
    lineStart >= MANIFEST_DOUBLE_NEWLINE_LENGTH &&
    original[lineStart - 1] == '\n' &&
    original[lineStart - MANIFEST_DOUBLE_NEWLINE_LENGTH] == '\n'
  ) {
    lineStart - 1
  } else {
    lineStart
  }
  val lineEnd = match.range.last + 1
  val cutEnd = if (lineEnd < original.length && original[lineEnd] == '\n') lineEnd + 1 else lineEnd
  val updated = original.substring(0, precedingBlankStart) + original.substring(cutEnd.coerceAtMost(original.length))
  if (updated != original) {
    manifestPath.toFile().writeText(updated)
  }
}
