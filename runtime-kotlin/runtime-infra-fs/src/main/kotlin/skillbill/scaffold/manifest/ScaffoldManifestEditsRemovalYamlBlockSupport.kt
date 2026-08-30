@file:Suppress("MagicNumber", "MaxLineLength")

package skillbill.scaffold.manifest

internal fun topLevelBlockLineRange(lines: List<String>, blockName: String): IntRange? {
  val start = lines.indexOfFirst { line -> line == "$blockName:" || line == "$blockName: {}" }
  if (start < 0 || lines[start] == "$blockName: {}") return null
  val next = lines.asSequence()
    .drop(start + 1)
    .indexOfFirst { line -> line.isNotBlank() && leadingSpaces(line) == 0 && line.contains(':') }
    .let { offset -> if (offset < 0) lines.size else start + 1 + offset }
  return start until next
}

internal fun listItemEnd(lines: List<String>, start: Int, maxExclusive: Int): Int {
  var idx = start + 1
  while (idx < maxExclusive && idx < lines.size) {
    val line = lines[idx]
    if (line.isNotBlank() && leadingSpaces(line) <= leadingSpaces(lines[start])) break
    idx += 1
  }
  return idx
}

internal fun collapseEmptyPointersBlock(text: String): String {
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
