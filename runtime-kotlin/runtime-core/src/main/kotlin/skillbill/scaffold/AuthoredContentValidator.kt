package skillbill.scaffold

import java.nio.file.Path

private val UNRESOLVED_PLACEHOLDER_PATTERN = Regex("""(?m)^\s*(?:[-*]\s*)?(?:TODO|FIXME)\b""")
private val WRAPPER_BOILERPLATE_HEADING_PATTERN = Regex("""^##\s+(Descriptor|Execution|Ceremony)\s*$""")
private val SELF_REFERENTIAL_CONTENT_POINTER_PATTERN =
  Regex("""Follow the instructions in\s+\[content\.md\]\(content\.md\)\.""", RegexOption.IGNORE_CASE)
private val FENCE_START_PATTERN = Regex("""^\s*(?:```|~~~)""")
private val TITLE_HEADING_PATTERN = Regex("""^#\s+\S.*$""")

internal fun validateAuthoredContent(contentFile: Path, text: String): List<String> {
  val body = markdownBodyAfterFrontmatter(text)
  val issues = mutableListOf<String>()
  val visibleLines = bodyVisibleLines(body)
  val hasSelfReferentialPointer = SELF_REFERENTIAL_CONTENT_POINTER_PATTERN.containsMatchIn(body)

  if (hasSelfReferentialPointer) {
    issues += "$contentFile: content.md contains self-referential wrapper pointer text instead of authored guidance"
  }
  if (visibleLines.isEmpty()) {
    issues += "$contentFile: content.md is missing required authored content"
  } else if (guidanceLinesBeyondTitle(visibleLines).isEmpty()) {
    issues += "$contentFile: content.md must include authored guidance beyond the title heading"
  }
  if (UNRESOLVED_PLACEHOLDER_PATTERN.containsMatchIn(body)) {
    issues += "$contentFile: content.md contains an unresolved TODO/FIXME placeholder"
  }
  wrapperBoilerplateHeadings(body).forEach { heading ->
    issues += "$contentFile: content.md must not contain generated wrapper boilerplate heading '$heading'"
  }

  return issues
}

private fun bodyVisibleLines(body: String): List<String> {
  val lines = mutableListOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence) {
      line.trim().takeIf(String::isNotEmpty)?.let(lines::add)
    }
  }
  return lines
}

private fun guidanceLinesBeyondTitle(visibleLines: List<String>): List<String> {
  val bodyAfterTitle = if (visibleLines.firstOrNull()?.let(TITLE_HEADING_PATTERN::matches) == true) {
    visibleLines.drop(1)
  } else {
    visibleLines
  }
  return bodyAfterTitle.filterNot { line -> SELF_REFERENTIAL_CONTENT_POINTER_PATTERN.matches(line) }
}

private fun wrapperBoilerplateHeadings(body: String): List<String> {
  val headings = mutableSetOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence) {
      WRAPPER_BOILERPLATE_HEADING_PATTERN.matchEntire(line.trim())?.let { match ->
        headings += "## ${match.groupValues[1]}"
      }
    }
  }
  return headings.toList()
}
