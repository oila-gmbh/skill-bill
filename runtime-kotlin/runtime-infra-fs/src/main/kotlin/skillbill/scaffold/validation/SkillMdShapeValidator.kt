package skillbill.scaffold.validation

import skillbill.error.InvalidSkillMdShapeError
import skillbill.scaffold.runtime.REQUIRED_GOVERNED_SECTIONS
import java.nio.file.Files
import java.nio.file.Path

private val FRONTMATTER_PATTERN = Regex("""(?s)\A---\n(.*?)\n---\n""")
private val ALLOWED_FRONTMATTER_KEYS = setOf("name", "description", "internal-for")
private val FENCE_PATTERN = Regex("""^\s*(?:```|~~~)""")
private val TABLE_PATTERN = Regex("""^\s*\|.*\|\s*$""")
private val STEP_HEADING_PATTERN = Regex("""^##\s+Step\s+\d+[a-z]?\b""", RegexOption.IGNORE_CASE)
private val MCP_INSTALL_PATTERN = Regex("""npm install -g|readian-mcp""", RegexOption.IGNORE_CASE)
private val TELEMETRY_PATTERN =
  Regex("""\b(?:_started|_finished)\b\s*MCP|telemetry_proxy_capabilities|skillbill_[a-z_]+_(?:started|finished)""")
private val ROUTING_RULE_PATTERN = Regex("""^\s*(?:Route|Routing rule|Routing rules):""", RegexOption.IGNORE_CASE)
private val RUN_CONTEXT_PATTERN = Regex("""^\s*`(?:Review session ID|Review run ID|Applied learnings):""")

/**
 * Validate the YAML frontmatter and (optionally) the canonical wrapper body shape of a markdown
 * file used by the governed skill pipeline.
 *
 * Frontmatter rules are ALWAYS enforced:
 *  - file must begin with a `---\n…\n---\n` block,
 *  - frontmatter must only contain keys [name, description],
 *  - both `name` and `description` must be present and non-blank.
 *
 * When [validateBodyShape] is true (used by SKILL.md wrapper callers while the wrapper still lives
 * on disk in subtasks 1–3), the body is additionally checked for wrapper-shape rules:
 *  - no fenced code blocks,
 *  - no H1 or H3+ headings,
 *  - no markdown tables,
 *  - exactly the [REQUIRED_GOVERNED_SECTIONS] H2 set in order,
 *  - no `## Step N:` headings, MCP install gates, telemetry instructions, routing rules, or
 *    run-context placeholder lines anywhere in the body.
 *
 * Error messages reference the file's actual `Path.fileName` rather than hard-coding "SKILL.md"
 * or "content.md", so the same validator works correctly from both callsites.
 */
internal fun validateSkillMdShape(path: Path, validateBodyShape: Boolean = false) {
  val text = Files.readString(path)
  val fileName = path.fileName?.toString() ?: path.toString()
  val frontmatterMatch = FRONTMATTER_PATTERN.find(text)
    ?: skillShapeFailure("$path: $fileName must begin with a YAML frontmatter block.")

  val frontmatter = parseFrontmatter(frontmatterMatch.groupValues[1])
  val unknownKeys = (frontmatter.keys - ALLOWED_FRONTMATTER_KEYS).sorted()
  if (unknownKeys.isNotEmpty()) {
    skillShapeFailure(
      "$path: $fileName frontmatter contains disallowed keys $unknownKeys; " +
        "only ${ALLOWED_FRONTMATTER_KEYS.sorted()} are allowed.",
    )
  }
  listOf("name", "description").forEach { requiredKey ->
    if (frontmatter[requiredKey].isNullOrBlank()) {
      skillShapeFailure("$path: $fileName frontmatter is missing required key '$requiredKey'.")
    }
  }

  if (!validateBodyShape) {
    return
  }

  val body = text.substring(frontmatterMatch.range.last + 1)
  val bodyStartLine = text.substring(0, frontmatterMatch.range.last + 1).count { it == '\n' } + 1
  val headings = mutableListOf<String>()
  var foundFirstH2 = false
  body.lineSequence().forEachIndexed { index, line ->
    val fileLine = bodyStartLine + index
    val stripped = line.trim()
    if (FENCE_PATTERN.containsMatchIn(line)) {
      skillShapeFailure("$path:$fileLine: fenced code blocks are not allowed in $fileName.")
    }
    if (line.startsWith("## ")) {
      headings += stripped
      foundFirstH2 = true
      return@forEachIndexed
    }
    if (!foundFirstH2) {
      if (stripped.isNotBlank()) {
        skillShapeFailure(
          "$path:$fileLine: intro paragraph or content is not allowed before the first H2.",
        )
      }
      return@forEachIndexed
    }
    validateBodyLine(path, fileName, fileLine, line)
  }

  if (headings.isEmpty()) {
    skillShapeFailure(
      "$path: $fileName must contain the canonical H2 sections $REQUIRED_GOVERNED_SECTIONS.",
    )
  }
  if (headings != REQUIRED_GOVERNED_SECTIONS) {
    skillShapeFailure(
      "$path: $fileName must contain exactly the H2 sections $REQUIRED_GOVERNED_SECTIONS " +
        "in that order; got $headings.",
    )
  }
}

internal fun parseSkillFrontmatter(text: String): Map<String, String> =
  FRONTMATTER_PATTERN.find(text)?.let { match -> parseFrontmatter(match.groupValues[1]) }.orEmpty()

internal fun markdownBodyAfterFrontmatter(text: String): String =
  FRONTMATTER_PATTERN.find(text)?.let { match -> text.substring(match.range.last + 1) } ?: text

private fun parseFrontmatter(frontmatter: String): Map<String, String> = frontmatter.lineSequence()
  .mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator < 0) {
      null
    } else {
      line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('"', '\'')
    }
  }
  .toMap()

private fun validateBodyLine(path: Path, fileName: String, fileLine: Int, line: String) {
  val matchedLabel = when {
    TABLE_PATTERN.containsMatchIn(line) -> "markdown table"
    STEP_HEADING_PATTERN.containsMatchIn(line) -> "'## Step N:' heading"
    MCP_INSTALL_PATTERN.containsMatchIn(line) -> "MCP install gate"
    TELEMETRY_PATTERN.containsMatchIn(line) -> "telemetry instructions"
    ROUTING_RULE_PATTERN.containsMatchIn(line) -> "routing rule"
    RUN_CONTEXT_PATTERN.containsMatchIn(line) -> "run-context placeholder line"
    line.startsWith("# ") -> "H1 heading"
    Regex("""^#{3,}\s+""").containsMatchIn(line) -> "H3+ heading"
    else -> null
  }
  if (matchedLabel != null) {
    skillShapeFailure(
      "$path:$fileLine: $fileName body must not contain $matchedLabel; matched '${line.trimEnd()}'.",
    )
  }
}

private fun skillShapeFailure(message: String): Nothing = throw InvalidSkillMdShapeError(message)
