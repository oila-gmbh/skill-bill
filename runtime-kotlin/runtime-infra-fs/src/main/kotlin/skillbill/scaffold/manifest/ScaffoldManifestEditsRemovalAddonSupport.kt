@file:Suppress("MagicNumber", "MaxLineLength")

package skillbill.scaffold.manifest

internal fun removeNamedPointerEntries(text: String, blockName: String, pointerName: String): String {
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

internal fun removeAddonUsageEntries(text: String, pointerName: String): String {
  val lines = text.split('\n').toMutableList()
  var changed = removeAddonUsageEntrypointItems(lines, pointerName)
  changed = removeAddonUsageCompanionPointers(lines, pointerName) || changed
  if (changed) {
    collapseEmptyCompanionPointerBlocks(lines)
    collapseEmptyNestedMappings(lines, "addon_usage")
  }
  return if (changed) lines.joinToString("\n") else text
}

internal fun removeAddonUsageEntrypointItems(lines: MutableList<String>, pointerName: String): Boolean {
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

internal fun removeAddonUsageCompanionPointers(lines: MutableList<String>, pointerName: String): Boolean {
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

internal fun isAddonUsageItemStart(line: String): Boolean =
  leadingSpaces(line) == NESTED_LIST_ITEM_INDENT && line.trimStart().startsWith("- slug:")

internal fun listItemContainsEntrypoint(
  lines: List<String>,
  start: Int,
  maxExclusive: Int,
  pointerName: String,
): Boolean {
  val end = listItemEnd(lines, start, maxExclusive)
  return lines.subList(start, end).any { line -> keyValue(line, "entrypoint") == pointerName }
}

internal fun collapseEmptyCompanionPointerBlocks(lines: MutableList<String>) {
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

internal fun collapseEmptyNestedMappings(lines: MutableList<String>, blockName: String) {
  removeEmptyNestedMappingBlocks(lines, blockName)
  collapseTopLevelMappingIfEmpty(lines, blockName)
}

internal fun removeEmptyNestedMappingBlocks(lines: MutableList<String>, blockName: String) {
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

internal fun collapseTopLevelMappingIfEmpty(lines: MutableList<String>, blockName: String) {
  val block = topLevelBlockLineRange(lines, blockName) ?: return
  val hasNestedMappings = lines.subList(block.first + 1, (block.last + 1).coerceAtMost(lines.size)).any { line ->
    line.isNotBlank() && leadingSpaces(line) == NESTED_MAPPING_INDENT
  }
  if (!hasNestedMappings) {
    lines[block.first] = "$blockName: {}"
  }
}
