package skillbill.scaffold

import java.nio.file.Files

internal fun renderWrapper(target: AuthoringTarget): String {
  // Source frontmatter from content.md — content.md is the authoring surface (since SKILL-40
  // subtask 1) and SKILL.md must be a faithful render of it. Sourcing from SKILL.md would let
  // wrapper frontmatter drift from authored description silently.
  val contentText = Files.readString(target.contentFile)
  val frontmatter = authoredContentFrontmatterBlock(contentText, target.contentFile, target.skillName)
  val executionBody = renderedAuthoredExecutionBody(contentText, target.contentFile, target.skillName)
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
