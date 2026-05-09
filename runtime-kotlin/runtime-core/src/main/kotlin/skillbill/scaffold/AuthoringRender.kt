package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files

private const val FRONTMATTER_PREFIX_LENGTH = 4
private val MARKDOWN_HEADING_PATTERN = Regex("""^(#{1,6})(\s+.*)$""")
private val TITLE_HEADING_PATTERN = Regex("""^#\s+\S.*$""")
private val FENCE_START_PATTERN = Regex("""^\s*(?:```|~~~)""")

internal fun renderWrapper(target: AuthoringTarget): String {
  // Source frontmatter from content.md — content.md is the authoring surface (since SKILL-40
  // subtask 1) and SKILL.md must be a faithful render of it. Sourcing from SKILL.md would let
  // wrapper frontmatter drift from authored description silently.
  val contentText = Files.readString(target.contentFile)
  val frontmatter = frontmatterBlock(contentText, target)
  val executionBody = renderedExecutionBody(contentText)
  val context =
    TemplateContext(
      skillName = target.skillName,
      family = target.family,
      platform = target.platform,
      area = target.area,
      displayName = target.displayName,
    )
  return buildString {
    append(frontmatter.trimEnd())
    appendLine()
    appendLine()
    append(renderDescriptorSection(context, defaultAreaFocus(target.area)).trimEnd())
    appendLine()
    appendLine()
    appendLine("## Execution")
    appendLine()
    append(executionBody.trimEnd())
    appendLine()
    appendLine()
    append(renderCeremonySection(context).trimEnd())
    appendLine()
  }
}

private fun frontmatterBlock(text: String, target: AuthoringTarget): String {
  if (!text.startsWith("---\n")) {
    throw SkillBillRuntimeException(
      "${target.contentFile}: content.md for skill '${target.skillName}' is missing YAML frontmatter.",
    )
  }
  val end = text.indexOf("\n---", startIndex = FRONTMATTER_PREFIX_LENGTH)
  if (end < 0) {
    throw SkillBillRuntimeException(
      "${target.contentFile}: content.md for skill '${target.skillName}' is missing YAML frontmatter.",
    )
  }
  return text.substring(0, end + "\n---".length)
}

private fun renderedExecutionBody(contentText: String): String {
  val authoredBody = stripAuthoredTitle(markdownBodyAfterFrontmatter(contentText)).trim('\r', '\n')
  return demoteMarkdownHeadings(authoredBody).trimEnd() + "\n"
}

private fun stripAuthoredTitle(body: String): String {
  val lines = body.lines().toMutableList()
  val titleIndex = lines.indexOfFirst { it.isNotBlank() }
  if (titleIndex >= 0 && TITLE_HEADING_PATTERN.matches(lines[titleIndex].trim())) {
    lines.removeAt(titleIndex)
    if (titleIndex < lines.size && lines[titleIndex].isBlank()) {
      lines.removeAt(titleIndex)
    }
  }
  return lines.joinToString("\n")
}

private fun demoteMarkdownHeadings(body: String): String {
  var inFence = false
  return body.lineSequence().joinToString("\n") { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      line
    } else if (!inFence) {
      MARKDOWN_HEADING_PATTERN.replace(line) { match -> "#${match.value}" }
    } else {
      line
    }
  }
}
