package skillbill.scaffold

import skillbill.nativeagent.NATIVE_AGENT_BUNDLE_FILE
import skillbill.nativeagent.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.parseNativeAgentSourceFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

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
  val skillClass = resolveSkillClassForSkill(target.skillName, target.contentFile)
  return buildString {
    append(frontmatter.trimEnd())
    appendLine()
    appendLine()
    append(renderDescriptorSection(context, defaultAreaFocus(target.area)).trimEnd())
    appendLine()
    appendLine()
    if (skillClass != null && skillClass.sections.isNotEmpty()) {
      append(renderClassSections(skillClass.sections).trimEnd())
      appendLine()
      appendLine()
    }
    appendLine("## Execution")
    if (executionBody.isNotBlank()) {
      appendLine()
      append(executionBody.trimEnd())
    }
    val subagentRuntimeNotes = renderGeneratedSubagentSpawnRuntimeNotes(target)
    if (subagentRuntimeNotes.isNotBlank()) {
      appendLine()
      appendLine()
      append(subagentRuntimeNotes.trimEnd())
    }
    appendLine()
    appendLine()
    append(renderCeremonySection(skillClass).trimEnd())
    appendLine()
  }
}

private fun renderGeneratedSubagentSpawnRuntimeNotes(target: AuthoringTarget): String {
  val nativeAgentDir = target.contentFile.parent.resolve(NATIVE_AGENT_SOURCE_DIR)
  if (!Files.isDirectory(nativeAgentDir)) {
    return ""
  }
  val specialists = nativeAgentSourceFiles(nativeAgentDir)
    .flatMap(::parseNativeAgentSourceFile)
    .map { source -> source.name }
  return renderSubagentSpawnRuntimeNotes(target.skillName, specialists)
}

private fun nativeAgentSourceFiles(nativeAgentDir: Path): List<Path> = Files.list(nativeAgentDir).use { stream ->
  stream
    .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
    .filter { file -> isNativeAgentSourceFile(file) }
    .sorted(Comparator.comparing { file -> file.fileName.toString() })
    .toList()
}

private fun isNativeAgentSourceFile(file: Path): Boolean {
  val fileName = file.fileName.toString()
  return fileName.endsWith(".md") || fileName == NATIVE_AGENT_BUNDLE_FILE
}
