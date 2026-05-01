package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path

private const val CONTENT_PREVIEW_LIMIT = 400

internal fun statusPayload(
  repoRoot: Path,
  target: AuthoringTarget,
  contentMode: String,
  issues: List<String> = emptyList(),
): Map<String, Any?> {
  val contentText = Files.readString(target.contentFile)
  val generationDrift = hasGenerationDrift(target)
  val completionStatus = contentCompletionStatus(contentText)
  val payload =
    linkedMapOf<String, Any?>(
      "skill_name" to target.skillName,
      "package" to target.packageName,
      "platform" to target.platform,
      "family" to target.family,
      "area" to target.area,
      "skill_file" to target.skillFile.toString(),
      "content_file" to target.contentFile.toString(),
      "completion_status" to completionStatus,
      "generation_drift" to generationDrift,
      "section_count" to parseContentSections(contentText).second.size,
      "sections" to sectionPayloads(contentText),
      "recommended_commands" to recommendedCommands(repoRoot, target, completionStatus, generationDrift, issues),
    )
  when (contentMode) {
    "preview" -> payload["content_preview"] = previewText(contentText, CONTENT_PREVIEW_LIMIT)
    "full" -> payload["content"] = contentText
  }
  if (issues.isNotEmpty()) {
    payload["issues"] = issues
  }
  return payload
}

internal fun recommendedCommands(
  repoRoot: Path,
  target: AuthoringTarget,
  completionStatus: String,
  generationDrift: Boolean,
  issues: List<String>,
): List<String> {
  val commands = mutableListOf("skill-bill show ${target.skillName} --repo-root $repoRoot")
  if (completionStatus != "complete") {
    commands += "skill-bill fill ${target.skillName} --repo-root $repoRoot --body-file <file>"
  }
  if (generationDrift) {
    commands += "skill-bill render --repo-root $repoRoot --skill-name ${target.skillName}"
  }
  if (issues.isNotEmpty()) {
    commands += "skill-bill validate --repo-root $repoRoot --skill-name ${target.skillName}"
  }
  return commands.distinct()
}

internal fun hasGenerationDrift(target: AuthoringTarget): Boolean =
  Files.readString(target.skillFile) != renderWrapper(target)
