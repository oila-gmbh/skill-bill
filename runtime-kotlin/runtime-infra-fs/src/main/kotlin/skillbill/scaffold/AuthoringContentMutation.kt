package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files

private val horizontalSkillFamilies: Map<String, String> =
  mapOf(
    "bill-feature-task" to "workflow",
    "bill-feature-verify" to "workflow",
    "bill-boundary-decisions" to "advisor",
    "bill-boundary-history" to "advisor",
    "bill-pr-description" to "advisor",
    "bill-feature-guard" to "advisor",
    "bill-feature-guard-cleanup" to "advisor",
    "bill-unit-test-value-check" to "advisor",
    "bill-code-review" to "advisor",
    "bill-quality-check" to "advisor",
  )

internal fun replaceSectionBody(text: String, sectionName: String, newBody: String): String {
  val (prefix, sections) = parseContentSections(text)
  val normalized = normalizeSectionHeading(sectionName)
  rejectGeneratedWrapperSectionEdit(normalized)
  var matched = false
  val updated = sections.map { (heading, body) ->
    if (heading == normalized) {
      matched = true
      heading to newBody.trimEnd()
    } else {
      heading to body
    }
  }
  if (!matched) {
    val available = sections.joinToString(", ") { (heading, _) -> heading.removePrefix("## ").trim() }
    throw SkillBillRuntimeException(
      "Unknown content section '${sectionHeadingLabel(sectionName)}'. Available sections: $available.",
    )
  }
  return renderContentSections(prefix, updated)
}

internal fun coerceFullContentText(target: AuthoringTarget, bodyText: String): String {
  val stripped = bodyText.trim()
  if (stripped.isBlank()) {
    throw SkillBillRuntimeException("Filled content must be non-empty.")
  }
  // The validator now requires a YAML frontmatter block at the head of content.md. If the
  // user-supplied body already starts with one we use it verbatim; otherwise we preserve
  // whatever frontmatter the existing content.md has so the writer never strips it.
  val (existingFrontmatter, _) = splitFrontmatter(Files.readString(target.contentFile))
  val (suppliedFrontmatter, suppliedBody) = splitFrontmatter(stripped)
  val frontmatter = suppliedFrontmatter ?: existingFrontmatter
    ?: throw SkillBillRuntimeException(
      "${target.contentFile}: content.md must already carry a YAML frontmatter block before " +
        "fill/edit (and the supplied body does not provide one). Run `skill-bill render " +
        "--skill-name ${target.skillName}` to regenerate the canonical frontmatter, or restore " +
        "the file from version control before retrying.",
    )
  val body = if (suppliedBody.startsWith("# ")) suppliedBody else "${fullContentTitle(target)}\n\n$suppliedBody"
  val trimmedBody = body.trimEnd()
  return "$frontmatter\n$trimmedBody\n"
}

private val FRONTMATTER_BLOCK = Regex("""(?s)\A---\n(.*?)\n---\n""")

private fun splitFrontmatter(text: String): Pair<String?, String> {
  val match = FRONTMATTER_BLOCK.find(text) ?: return null to text
  val frontmatter = text.substring(match.range.first, match.range.last + 1)
  val rest = text.substring(match.range.last + 1).trimStart('\n')
  return frontmatter to rest
}

internal fun sectionHeadingLabel(sectionName: String): String =
  normalizeSectionHeading(sectionName).removePrefix("## ").trim()

internal fun inferFamily(skillName: String): String {
  horizontalSkillFamilies[skillName]?.let { family -> return family }
  val slug = skillName.removePrefix("bill-")
  return when {
    "-code-review-" in skillName || slug.endsWith("code-review") -> "code-review"
    slug.endsWith("quality-check") -> "quality-check"
    slug.endsWith("feature-task") || slug.endsWith("feature-implement") -> "feature-implement"
    slug.endsWith("feature-verify") -> "feature-verify"
    else -> slug
  }
}

internal fun inferArea(skillName: String, family: String): String =
  if (family == "code-review" && "-code-review-" in skillName) skillName.substringAfter("-code-review-") else ""

private fun fullContentTitle(target: AuthoringTarget): String =
  Files.readString(target.contentFile).lineSequence().firstOrNull { line -> line.trim().startsWith("# ") }?.trim()
    ?: "# Content"

private fun normalizeSectionHeading(sectionName: String): String {
  val heading = sectionName.trim()
  if (heading.isBlank()) {
    throw SkillBillRuntimeException("Section name must be non-empty.")
  }
  return if (heading.startsWith("## ")) heading else "## $heading"
}

private fun rejectGeneratedWrapperSectionEdit(normalizedHeading: String) {
  val label = normalizedHeading.removePrefix("## ").trim()
  val generatedHeading = REQUIRED_GOVERNED_SECTIONS.firstOrNull { heading ->
    heading.removePrefix("## ").trim().equals(label, ignoreCase = true)
  } ?: return
  throw SkillBillRuntimeException(
    "Cannot edit generated wrapper section '$generatedHeading' through content.md. " +
      "Descriptor, Execution, and Ceremony are generated into SKILL.md render/install output. " +
      "Edit authored content.md sections for behavior changes, or update content.md frontmatter " +
      "and platform.yaml manifest fields for generated descriptor metadata.",
  )
}
