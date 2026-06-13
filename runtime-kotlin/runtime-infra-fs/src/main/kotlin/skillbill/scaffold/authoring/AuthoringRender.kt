package skillbill.scaffold.authoring

import skillbill.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.platformpack.resolveSkillClassForSkill
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.rendering.renderCeremonySection
import skillbill.scaffold.rendering.renderClassSections
import skillbill.scaffold.rendering.renderDescriptorSection
import skillbill.scaffold.rendering.renderFrontmatter
import skillbill.scaffold.rendering.renderSubagentSpawnRuntimeNotes
import skillbill.scaffold.runtime.TemplateContext
import skillbill.scaffold.validation.parseSkillFrontmatter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun renderWrapper(target: AuthoringTarget): String {
  // Source frontmatter from content.md, then render the wrapper frontmatter through the canonical
  // YAML scalar writer. This preserves the authored values without leaking invalid plain scalars
  // such as descriptions containing ": " into installed SKILL.md files.
  val contentText = Files.readString(target.contentFile)
  authoredContentFrontmatterBlock(contentText, target.contentFile, target.skillName)
  val sourceFrontmatter = parseSkillFrontmatter(contentText)
  val frontmatter = renderFrontmatter(
    skillName = sourceFrontmatter["name"].orEmpty(),
    description = sourceFrontmatter["description"].orEmpty(),
  )
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
    val reviewComposition = renderReviewCompositionSection(target)
    if (reviewComposition.isNotBlank()) {
      append(reviewComposition.trimEnd())
      appendLine()
      appendLine()
    }
    val governedAddons = renderGovernedAddonsSection(target)
    if (governedAddons.isNotBlank()) {
      append(governedAddons.trimEnd())
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

private fun renderGovernedAddonsSection(target: AuthoringTarget): String {
  val addons = target.addonUsage
  if (addons.isEmpty()) {
    return ""
  }
  return buildString {
    appendLine("## Governed Add-Ons")
    appendLine()
    appendLine(
      "This platform pack declares add-on usage for this skill in `platform.yaml`. Runtime agents MUST resolve " +
        "these add-ons only after stack routing has selected `${target.platform}`.",
    )
    appendLine()
    appendLine(
      "Start from `Selected add-ons: none`. Scan each declared entrypoint's `## Activation signals` and " +
        "`## Section index` headings first, select only add-ons whose cues match the scoped work, then open " +
        "only the selected entrypoint and companion pointers needed for that scope.",
    )
    appendLine(
      "Report the final selection as `Selected add-ons: <slug, ...>` and pass the selected add-ons into any " +
        "specialist passes. Add-ons enrich this routed skill; they do not create standalone skill names or " +
        "bypass stack routing.",
    )
    appendLine()
    appendLine("### Declared Add-Ons")
    appendLine()
    addons.forEach { addon ->
      appendLine("- ${renderAddonSelection(addon)}")
    }
  }
}

private fun renderAddonSelection(addon: GovernedAddonSelection): String {
  val companionText = if (addon.companionPointers.isEmpty()) {
    "companions `none`"
  } else {
    "companions ${addon.companionPointers.joinToString(", ") { pointer -> "`$pointer`" }}"
  }
  return "`${addon.slug}`: entrypoint `${addon.entrypoint}`; $companionText."
}

private fun renderReviewCompositionSection(target: AuthoringTarget): String {
  val baselineLayers = target.codeReviewComposition?.baselineLayers.orEmpty()
  return if (baselineLayers.isEmpty()) {
    ""
  } else {
    buildString {
      appendLine("## Review Composition")
      appendLine()
      appendLine(
        "This platform pack declares code-review composition in `platform.yaml`. Runtime agents MUST run each " +
          "required baseline layer before selecting or running pack-local specialists.",
      )
      appendLine()
      appendLine(
        "Scope propagation is mandatory: pass the same review IDs, applied learnings, AGENTS guidance, " +
          "changed files, and stack signals into every baseline layer and then into any pack-local " +
          "specialist review.",
      )
      appendLine(
        "Keep baseline-layer findings attributed to that layer before merging and deduplicating them with pack-local " +
          "specialist findings.",
      )
      appendLine()
      appendLine("### Required Baseline Layers")
      appendLine()
      baselineLayers.forEach { layer ->
        appendLine("- ${renderBaselineLayerLabel(layer)}")
      }
    }
  }
}

private fun renderBaselineLayerLabel(layer: CodeReviewBaselineLayer): String =
  "`" + layer.platform + "/" + layer.skill + "` with scope `" + layer.scope.wireValue + "`, mode `" +
    layer.mode.wireValue + "`, required `" + layer.required + "`."

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
