@file:Suppress("MagicNumber", "MaxLineLength")

package skillbill.scaffold.manifest

internal const val NESTED_MAPPING_INDENT = 2
internal const val NESTED_LIST_ITEM_INDENT = 4
internal const val COMPANION_POINTER_HEADER_INDENT = 6
internal const val COMPANION_POINTER_ITEM_INDENT = 8

internal fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.length

internal fun unquoteYamlScalar(value: String): String = value.removeSurrounding("\"").removeSurrounding("'")

internal fun yamlListScalar(line: String): String? {
  val trimmed = line.trim()
  return if (trimmed.startsWith("- ")) unquoteYamlScalar(trimmed.removePrefix("- ").trim()) else null
}

internal fun namedListEntryValue(line: String, key: String): String? {
  val trimmed = line.trim()
  val prefix = "- $key:"
  return if (trimmed.startsWith(prefix)) unquoteYamlScalar(trimmed.removePrefix(prefix).trim()) else null
}

internal fun keyValue(line: String, key: String): String? {
  val trimmed = line.trim()
  val prefix = "$key:"
  return if (trimmed.startsWith(prefix)) unquoteYamlScalar(trimmed.removePrefix(prefix).trim()) else null
}

internal fun isNestedMappingHeader(line: String): Boolean =
  leadingSpaces(line) == NESTED_MAPPING_INDENT && line.trimEnd().endsWith(":")

internal fun nestedMappingEnd(lines: List<String>, start: Int, maxExclusive: Int): Int = lines.asSequence()
  .drop(start + 1)
  .take(maxExclusive - start - 1)
  .indexOfFirst { line -> line.isNotBlank() && leadingSpaces(line) <= NESTED_MAPPING_INDENT }
  .let { offset -> if (offset < 0) maxExclusive else start + 1 + offset }

internal fun nestedMappingHasListItem(lines: List<String>, start: Int, end: Int): Boolean =
  lines.subList(start + 1, end.coerceAtMost(lines.size)).any { line ->
    line.isNotBlank() && leadingSpaces(line) == NESTED_LIST_ITEM_INDENT && line.trimStart().startsWith("- ")
  }
