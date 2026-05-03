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
  "## Execution\n\nFollow the instructions in [content.md](content.md).\n"

internal const val CANONICAL_CEREMONY_SECTION: String =
  "## Ceremony\n\nFollow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).\n"

internal data class TemplateContext(
  val skillName: String,
  val family: String,
  val platform: String,
  val area: String,
  val displayName: String,
)

internal fun displayNameFromSlug(slug: String): String = slug.split('-').joinToString(" ") { part ->
  part.replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
  }
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
  "audit-rubrics.md" to repoRoot.resolve("skills/bill-feature-verify/audit-rubrics.md"),
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
  ) && skillName.endsWith("-feature-verify") -> listOf(
    "shell-ceremony.md",
    "telemetry-contract.md",
    "audit-rubrics.md",
  )
  skillName == "bill-pr-description" -> listOf("shell-ceremony.md", "telemetry-contract.md")
  else -> emptyList()
}

internal fun requireSupportingFileTarget(skillName: String, fileName: String, repoRoot: Path): Path =
  supportingFileTargets(repoRoot)[fileName] ?: throw MissingSupportingFileTargetError(
    "Runtime supporting file '$fileName' is not registered for '$skillName'.",
  )
