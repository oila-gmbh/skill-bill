package skillbill.scaffold

import skillbill.scaffold.model.CodeReviewBaselineLayer
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
  val completionStatus = contentCompletionStatus(contentText)
  val payload =
    linkedMapOf<String, Any?>(
      "skill_name" to target.skillName,
      "package" to target.packageName,
      "platform" to target.platform,
      "family" to target.family,
      "area" to target.area,
      "content_file" to target.contentFile.toString(),
      "render_command" to "skill-bill render ${target.skillName} --repo-root $repoRoot",
      "completion_status" to completionStatus,
      "section_count" to parseContentSections(contentText).second.size,
      "sections" to sectionPayloads(contentText),
      "recommended_commands" to recommendedCommands(repoRoot, target, completionStatus, issues),
    )
  target.codeReviewComposition?.baselineLayers?.takeIf { it.isNotEmpty() }?.let { baselineLayers ->
    payload["review_composition"] =
      mapOf(
        "source" to "platform.yaml",
        "summary" to baselineLayerSummary(baselineLayers),
        "baseline_layers" to baselineLayers.map(::baselineLayerPayload),
      )
  }
  when (contentMode) {
    "preview" -> payload["content_preview"] = previewText(contentText, CONTENT_PREVIEW_LIMIT)
    "full" -> payload["content"] = contentText
  }
  if (issues.isNotEmpty()) {
    payload["issues"] = issues
  }
  return payload
}

private fun baselineLayerSummary(baselineLayers: List<CodeReviewBaselineLayer>): String {
  val requiredCount = baselineLayers.count { it.required }
  val optionalCount = baselineLayers.size - requiredCount
  val layerText = buildList {
    if (requiredCount > 0) add("$requiredCount required")
    if (optionalCount > 0) add("$optionalCount optional")
  }.joinToString(" and ")
  return "Run $layerText baseline layer(s) before pack-local specialists."
}

private fun baselineLayerPayload(layer: CodeReviewBaselineLayer): Map<String, Any?> = mapOf(
  "platform" to layer.platform,
  "skill" to layer.skill,
  "scope" to layer.scope.wireValue,
  "required" to layer.required,
  "mode" to layer.mode.wireValue,
)

internal fun recommendedCommands(
  repoRoot: Path,
  target: AuthoringTarget,
  completionStatus: String,
  issues: List<String>,
): List<String> {
  val commands = mutableListOf("skill-bill show ${target.skillName} --repo-root $repoRoot")
  if (completionStatus != "complete") {
    commands += "skill-bill fill ${target.skillName} --repo-root $repoRoot --body-file <file>"
  }
  if (issues.isNotEmpty()) {
    commands += "skill-bill validate --repo-root $repoRoot --skill-name ${target.skillName}"
  }
  return commands.distinct()
}
