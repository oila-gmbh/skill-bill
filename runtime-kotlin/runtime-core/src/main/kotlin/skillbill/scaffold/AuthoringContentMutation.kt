package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files

private val horizontalSkillFamilies: Map<String, String> =
  mapOf(
    "bill-feature-implement" to "workflow",
    "bill-feature-verify" to "workflow",
    "bill-grill-plan" to "advisor",
    "bill-boundary-decisions" to "advisor",
    "bill-boundary-history" to "advisor",
    "bill-pr-description" to "advisor",
    "bill-create-skill" to "advisor",
    "bill-skill-remove" to "advisor",
    "bill-feature-guard" to "advisor",
    "bill-feature-guard-cleanup" to "advisor",
    "bill-unit-test-value-check" to "advisor",
    "bill-code-review" to "advisor",
    "bill-quality-check" to "advisor",
  )

internal fun replaceSectionBody(text: String, sectionName: String, newBody: String): String {
  val (prefix, sections) = parseContentSections(text)
  val normalized = normalizeSectionHeading(sectionName)
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
  val rendered = if (stripped.startsWith("# ")) stripped else "${fullContentTitle(target)}\n\n$stripped"
  return rendered.trimEnd() + "\n"
}

internal fun sectionHeadingLabel(sectionName: String): String =
  normalizeSectionHeading(sectionName).removePrefix("## ").trim()

internal fun inferFamily(skillName: String): String {
  horizontalSkillFamilies[skillName]?.let { family -> return family }
  val slug = skillName.removePrefix("bill-")
  return when {
    "-code-review-" in skillName || slug.endsWith("code-review") -> "code-review"
    slug.endsWith("quality-check") -> "quality-check"
    slug.endsWith("feature-implement") -> "feature-implement"
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
