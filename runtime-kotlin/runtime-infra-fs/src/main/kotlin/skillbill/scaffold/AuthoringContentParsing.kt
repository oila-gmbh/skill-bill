package skillbill.scaffold

private const val SECTION_PREVIEW_LIMIT = 200

internal fun parseContentSections(text: String): Pair<String, List<Pair<String, String>>> {
  val prefixLines = mutableListOf<String>()
  val sections = mutableListOf<Pair<String, String>>()
  var currentHeading: String? = null
  val currentLines = mutableListOf<String>()
  var inFence = false
  text.lineSequence().forEach { rawLine ->
    val line = "$rawLine\n"
    val stripped = rawLine.trimEnd()
    if (stripped.trimStart().startsWith("```") || stripped.trimStart().startsWith("~~~")) {
      inFence = !inFence
      if (currentHeading == null) prefixLines += line else currentLines += line
      return@forEach
    }
    if (!inFence && stripped.startsWith("## ")) {
      val heading = currentHeading
      if (heading == null) {
        currentHeading = stripped
      } else {
        sections += heading to currentLines.joinToString("")
        currentHeading = stripped
        currentLines.clear()
      }
      return@forEach
    }
    if (currentHeading == null) prefixLines += line else currentLines += line
  }
  currentHeading?.let { heading -> sections += heading to currentLines.joinToString("") }
  return prefixLines.joinToString("") to sections
}

internal fun renderContentSections(prefix: String, sections: List<Pair<String, String>>): String {
  val blocks = mutableListOf<String>()
  val prefixText = prefix.trimEnd()
  if (prefixText.isNotBlank()) {
    blocks += prefixText
  }
  sections.forEach { (heading, body) ->
    val sectionBody = body.trimEnd()
    blocks += if (sectionBody.isBlank()) "$heading\n" else "$heading\n\n$sectionBody"
  }
  return blocks.joinToString("\n\n").trimEnd() + "\n"
}

internal fun sectionPayloads(text: String): List<Map<String, Any?>> =
  parseContentSections(text).second.map { (heading, body) ->
    mapOf(
      "heading" to heading.removePrefix("## ").trim(),
      "status" to sectionCompletionStatus(body),
      "line_count" to body.lines().count { line -> line.isNotBlank() },
      "preview" to previewText(body, SECTION_PREVIEW_LIMIT),
    )
  }

internal fun contentCompletionStatus(text: String): String {
  val visibleLines = text.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() }
  if (visibleLines.size <= 1 || hasUnresolvedPlaceholder(text)) {
    return "draft"
  }
  val sections = parseContentSections(text).second
  return if (sections.isNotEmpty() && sections.any { (_, body) -> sectionCompletionStatus(body) != "complete" }) {
    "draft"
  } else {
    "complete"
  }
}

internal fun hasUnresolvedPlaceholder(text: String): Boolean =
  Regex("""(?m)^\s*(?:[-*]\s*)?(?:TODO|FIXME)\b""").containsMatchIn(text)

internal fun previewText(text: String, limit: Int): String {
  val collapsed = text.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() }.joinToString(" ")
  return if (collapsed.length <= limit) collapsed else collapsed.take(limit - 1).trimEnd() + "..."
}

private fun sectionCompletionStatus(body: String): String = when {
  body.isBlank() -> "empty"
  hasUnresolvedPlaceholder(body) -> "draft"
  else -> "complete"
}
