@file:Suppress("CyclomaticComplexMethod", "MaxLineLength")

package skillbill.scaffold

import skillbill.error.MissingSupportingFileTargetError
import java.nio.file.Path

internal const val SHELL_CONTRACT_VERSION: String = "1.1"
internal const val SCAFFOLD_PAYLOAD_VERSION: String = "1.0"
internal const val CONTENT_BODY_FILENAME: String = "content.md"

internal val APPROVED_CODE_REVIEW_AREAS: Set<String> =
  setOf(
    "architecture",
    "performance",
    "platform-correctness",
    "security",
    "testing",
    "api-contracts",
    "persistence",
    "reliability",
    "ui",
    "ux-accessibility",
  )

internal val REQUIRED_GOVERNED_SECTIONS: List<String> =
  listOf("## Descriptor", "## Execution", "## Ceremony")

internal val REQUIRED_CONTENT_SECTIONS: List<String> =
  listOf(
    "## Description",
    "## Specialist Scope",
    "## Inputs",
    "## Outputs Contract",
    "## Execution Mode Reporting",
    "## Telemetry Ceremony Hooks",
  )

internal const val CANONICAL_EXECUTION_SECTION: String =
  "## Execution\n\nRead the sibling `content.md` for authored execution guidance.\n"

internal const val CANONICAL_CEREMONY_SECTION: String =
  "## Ceremony\n\nKeep `shell-ceremony.md` as the shared ceremony sidecar.\n"

internal data class TemplateContext(
  val skillName: String,
  val family: String,
  val platform: String,
  val area: String,
  val displayName: String,
)

internal fun defaultAreaFocus(area: String): String = "Review ${area.replace('-', ' ')} regressions."

internal fun inferSkillDescription(context: TemplateContext): String = when {
  context.family == "code-review" && context.area.isNotBlank() ->
    "Use when reviewing ${context.displayName} changes for ${context.area} risks."
  context.family == "code-review" ->
    "Use when reviewing changes in ${context.displayName} codebases."
  context.family == "quality-check" ->
    "Use when validating ${context.displayName} changes with the shared quality-check contract."
  context.family == "feature-implement" ->
    "Use when implementing ${context.displayName} changes end to end."
  context.family == "feature-verify" ->
    "Use when verifying ${context.displayName} changes before release."
  else ->
    "Use for ${context.displayName} work."
}

internal fun renderFrontmatter(skillName: String, description: String): String = buildString {
  appendLine("---")
  appendLine("name: $skillName")
  appendLine("description: $description")
  appendLine("---")
}

internal fun renderDescriptorSection(context: TemplateContext, areaFocus: String): String = buildString {
  appendLine("## Descriptor")
  appendLine()
  appendLine("- Skill: ${context.skillName}")
  appendLine("- Family: ${context.family}")
  appendLine("- Platform: ${context.platform}")
  if (context.area.isNotBlank()) {
    appendLine("- Area: ${context.area}")
    appendLine("- Focus: $areaFocus")
  }
}

internal fun renderContentBody(context: TemplateContext, description: String, contentBody: String? = null): String {
  val specialistScope =
    contentBody?.trimEnd().takeUnless { it.isNullOrBlank() }
      ?: "Use this content surface to author the ${context.displayName} execution guidance."
  return buildString {
    append(renderFrontmatter(context.skillName, description))
    appendLine("## Description")
    appendLine()
    appendLine(description)
    appendLine()
    appendLine("## Specialist Scope")
    appendLine()
    appendLine(specialistScope)
    appendLine()
    appendLine("## Inputs")
    appendLine()
    appendLine("- Repo context")
    appendLine("- Relevant diffs or scope")
    appendLine()
    appendLine("## Outputs Contract")
    appendLine()
    appendLine("- Structured execution output")
    appendLine()
    appendLine("## Execution Mode Reporting")
    appendLine()
    appendLine("- Report the chosen execution mode in the wrapper.")
    appendLine()
    appendLine("## Telemetry Ceremony Hooks")
    appendLine()
    appendLine("- Keep telemetry hooks in the shared sidecar.")
  }
}

internal fun renderSkillBody(context: TemplateContext, description: String, areaFocus: String = ""): String =
  buildString {
    append(renderFrontmatter(context.skillName, description))
    append(renderDescriptorSection(context, areaFocus))
    appendLine()
    append(CANONICAL_EXECUTION_SECTION)
    appendLine()
    append(CANONICAL_CEREMONY_SECTION)
  }

internal fun renderAddonBody(skillName: String, description: String, explicitBody: String?): String {
  val body = explicitBody?.takeIf { it.isNotBlank() } ?: buildString {
    appendLine("# $skillName")
    appendLine()
    appendLine(description)
    appendLine()
    appendLine("TODO: author the add-on body.")
  }
  return if (body.endsWith('\n')) body else "$body\n"
}

internal val ORCHESTRATION_PLAYBOOKS: Map<String, String> =
  mapOf(
    "review-scope" to "orchestration/review-scope/PLAYBOOK.md",
    "stack-routing" to "orchestration/stack-routing/PLAYBOOK.md",
    "review-orchestrator" to "orchestration/review-orchestrator/PLAYBOOK.md",
    "review-specialist-contract" to "orchestration/review-orchestrator/specialist-contract.md",
    "review-delegation" to "orchestration/review-delegation/PLAYBOOK.md",
    "telemetry-contract" to "orchestration/telemetry-contract/PLAYBOOK.md",
    "shell-content-contract" to "orchestration/shell-content-contract/PLAYBOOK.md",
  )

internal val ORCHESTRATION_SIDECARS: Map<String, String> =
  mapOf("shell-ceremony" to "orchestration/shell-content-contract/shell-ceremony.md")

internal fun supportingFileTargets(repoRoot: Path): Map<String, Path> = mapOf(
  "review-scope.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-scope")),
  "stack-routing.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("stack-routing")),
  "review-orchestrator.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-orchestrator")),
  "specialist-contract.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-specialist-contract")),
  "review-delegation.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("review-delegation")),
  "telemetry-contract.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("telemetry-contract")),
  "shell-content-contract.md" to repoRoot.resolve(ORCHESTRATION_PLAYBOOKS.getValue("shell-content-contract")),
  "shell-ceremony.md" to repoRoot.resolve(ORCHESTRATION_SIDECARS.getValue("shell-ceremony")),
  "android-compose-implementation.md" to repoRoot.resolve(
    "platform-packs/kmp/addons/android-compose-implementation.md",
  ),
  "android-navigation-implementation.md" to repoRoot.resolve(
    "platform-packs/kmp/addons/android-navigation-implementation.md",
  ),
  "android-interop-implementation.md" to repoRoot.resolve(
    "platform-packs/kmp/addons/android-interop-implementation.md",
  ),
  "android-design-system-implementation.md" to repoRoot.resolve(
    "platform-packs/kmp/addons/android-design-system-implementation.md",
  ),
  "android-r8-implementation.md" to repoRoot.resolve("platform-packs/kmp/addons/android-r8-implementation.md"),
  "android-compose-edge-to-edge.md" to repoRoot.resolve("platform-packs/kmp/addons/android-compose-edge-to-edge.md"),
  "android-compose-adaptive-layouts.md" to repoRoot.resolve(
    "platform-packs/kmp/addons/android-compose-adaptive-layouts.md",
  ),
)

internal fun requiredSupportingFilesForSkill(skillName: String): List<String> = when {
  skillName == "bill-code-review" -> listOf(
    "review-scope.md",
    "stack-routing.md",
    "review-delegation.md",
    "telemetry-contract.md",
    "shell-content-contract.md",
    "shell-ceremony.md",
  )
  skillName.startsWith("bill-") && skillName.endsWith("-code-review") -> listOf(
    "review-scope.md",
    "stack-routing.md",
    "review-orchestrator.md",
    "specialist-contract.md",
    "review-delegation.md",
    "telemetry-contract.md",
    "shell-ceremony.md",
  )
  skillName.startsWith("bill-") && "-code-review-" in skillName -> listOf(
    "review-orchestrator.md",
    "telemetry-contract.md",
    "shell-ceremony.md",
  )
  skillName == "bill-quality-check" -> listOf("stack-routing.md", "telemetry-contract.md", "shell-ceremony.md")
  skillName.startsWith("bill-") && skillName.endsWith("-quality-check") -> listOf(
    "stack-routing.md",
    "telemetry-contract.md",
    "shell-ceremony.md",
  )
  skillName.startsWith("bill-") && skillName.endsWith("-feature-implement") -> listOf(
    "shell-ceremony.md",
    "telemetry-contract.md",
    "android-compose-implementation.md",
    "android-navigation-implementation.md",
    "android-interop-implementation.md",
    "android-design-system-implementation.md",
    "android-r8-implementation.md",
    "android-compose-edge-to-edge.md",
    "android-compose-adaptive-layouts.md",
  )
  skillName.startsWith(
    "bill-",
  ) && skillName.endsWith("-feature-verify") -> listOf("shell-ceremony.md", "telemetry-contract.md")
  skillName == "bill-pr-description" -> listOf("shell-ceremony.md", "telemetry-contract.md")
  else -> emptyList()
}

internal fun requireSupportingFileTarget(skillName: String, fileName: String, repoRoot: Path): Path =
  supportingFileTargets(repoRoot)[fileName] ?: throw MissingSupportingFileTargetError(
    "Runtime supporting file '$fileName' is not registered for '$skillName'.",
  )
