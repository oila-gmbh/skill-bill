package skillbill.scaffold.validation

import java.nio.file.Files
import java.nio.file.Path

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
  val bodyStartOffset = validateSkillMdFrontmatter(path, fileName, text)
  if (!validateBodyShape) {
    return
  }
  validateSkillMdBodyShape(path, fileName, text, bodyStartOffset)
}

internal fun parseSkillFrontmatter(text: String): Map<String, String> =
  SKILL_MD_FRONTMATTER_PATTERN.find(text)?.let { match -> parseSkillMdFrontmatter(match.groupValues[1]) }.orEmpty()

internal fun markdownBodyAfterFrontmatter(text: String): String =
  SKILL_MD_FRONTMATTER_PATTERN.find(text)?.let { match -> text.substring(match.range.last + 1) } ?: text
