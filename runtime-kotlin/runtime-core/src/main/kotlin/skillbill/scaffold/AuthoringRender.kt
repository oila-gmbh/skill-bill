package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files

private const val FRONTMATTER_PREFIX_LENGTH = 4

internal fun renderWrapper(target: AuthoringTarget): String {
  val frontmatter = frontmatterBlock(Files.readString(target.skillFile))
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
    append(CANONICAL_EXECUTION_SECTION.trimEnd())
    appendLine()
    appendLine()
    append(renderCeremonySection(context).trimEnd())
    appendLine()
  }
}

private fun frontmatterBlock(text: String): String {
  if (!text.startsWith("---\n")) {
    throw SkillBillRuntimeException("SKILL.md is missing YAML frontmatter.")
  }
  val end = text.indexOf("\n---", startIndex = FRONTMATTER_PREFIX_LENGTH)
  if (end < 0) {
    throw SkillBillRuntimeException("SKILL.md is missing YAML frontmatter.")
  }
  return text.substring(0, end + "\n---".length)
}
