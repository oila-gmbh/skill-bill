package skillbill.scaffold.validation

import skillbill.scaffold.runtime.REQUIRED_GOVERNED_SECTIONS
import java.nio.file.Path

internal fun validateSkillMdFrontmatter(path: Path, fileName: String, text: String): Int {
  val frontmatterMatch = SKILL_MD_FRONTMATTER_PATTERN.find(text)
    ?: skillShapeFailure("$path: $fileName must begin with a YAML frontmatter block.")

  val frontmatter = parseSkillMdFrontmatter(frontmatterMatch.groupValues[1])
  val unknownKeys = (frontmatter.keys - SKILL_MD_ALLOWED_FRONTMATTER_KEYS).sorted()
  if (unknownKeys.isNotEmpty()) {
    skillShapeFailure(
      "$path: $fileName frontmatter contains disallowed keys $unknownKeys; " +
        "only ${SKILL_MD_ALLOWED_FRONTMATTER_KEYS.sorted()} are allowed.",
    )
  }
  listOf("name", "description").forEach { requiredKey ->
    if (frontmatter[requiredKey].isNullOrBlank()) {
      skillShapeFailure("$path: $fileName frontmatter is missing required key '$requiredKey'.")
    }
  }
  return frontmatterMatch.range.last + 1
}

internal fun validateSkillMdBodyShape(path: Path, fileName: String, text: String, bodyStartOffset: Int) {
  val body = text.substring(bodyStartOffset)
  val bodyStartLine = text.substring(0, bodyStartOffset).count { it == '\n' } + 1
  val headings = mutableListOf<String>()
  var foundFirstSecondLevelHeading = false
  body.lineSequence().forEachIndexed { index, line ->
    val fileLine = bodyStartLine + index
    val stripped = line.trim()
    if (SKILL_MD_FENCE_PATTERN.containsMatchIn(line)) {
      skillShapeFailure("$path:$fileLine: fenced code blocks are not allowed in $fileName.")
    }
    if (line.startsWith("## ")) {
      headings += stripped
      foundFirstSecondLevelHeading = true
      return@forEachIndexed
    }
    if (!foundFirstSecondLevelHeading) {
      if (stripped.isNotBlank()) {
        skillShapeFailure(
          "$path:$fileLine: intro paragraph or content is not allowed before the first H2.",
        )
      }
      return@forEachIndexed
    }
    validateSkillMdBodyLine(path, fileName, fileLine, line)
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
