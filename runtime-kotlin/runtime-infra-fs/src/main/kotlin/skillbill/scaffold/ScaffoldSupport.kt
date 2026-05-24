@file:Suppress("CyclomaticComplexMethod", "MaxLineLength")

package skillbill.scaffold

import skillbill.error.MissingSupportingFileTargetError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS as POLICY_APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.PLATFORM_PACK_PRESETS as POLICY_PLATFORM_PACK_PRESETS
import skillbill.scaffold.policy.PLATFORM_PACK_SHELL_CONTRACT_VERSION as POLICY_SHELL_CONTRACT_VERSION
import skillbill.scaffold.policy.SCAFFOLD_PAYLOAD_VERSION as POLICY_SCAFFOLD_PAYLOAD_VERSION
import skillbill.scaffold.policy.displayNameFromSlug as policyDisplayNameFromSlug

// SKILL-52.1 subtask 2: the canonical shell-contract version now lives in `runtime-domain` under
// `skillbill.scaffold.policy.PLATFORM_PACK_SHELL_CONTRACT_VERSION`. This alias keeps the existing
// internal callsites compiling against a single source of truth — bumping the version in one place
// updates both the manifest renderer and the shell validation seam.
internal val SHELL_CONTRACT_VERSION: String get() = POLICY_SHELL_CONTRACT_VERSION

// SKILL-52.1 subtask 2: the canonical scaffold-payload version now lives in
// `runtime-domain` under `skillbill.scaffold.policy`. This alias keeps the existing internal
// callsites and `ScaffoldCatalog` projection working without changing the source-of-truth.
internal val SCAFFOLD_PAYLOAD_VERSION: String get() = POLICY_SCAFFOLD_PAYLOAD_VERSION
internal const val CONTENT_BODY_FILENAME: String = "content.md"

// SKILL-52.1 subtask 2: approved code-review areas and built-in platform-pack preset projection
// are owned by `skillbill.scaffold.policy` in `runtime-domain`. The aliases below keep the
// existing infra-fs callsites (ScaffoldCatalog, ScaffoldService, ShellContentLoader) compiling
// against a single source of truth.
internal val APPROVED_CODE_REVIEW_AREAS: Set<String> get() = POLICY_APPROVED_CODE_REVIEW_AREAS

// F-001: Authoritative source for the pre-shell vs shelled family taxonomy. The desktop wizard's
// `ScaffoldCatalog` delegates to these so the runtime stays the single source of truth.
internal val SHELLED_FAMILIES: Set<String> = setOf("code-review", "quality-check")
internal val PRE_SHELL_FAMILIES: Set<String> = setOf("feature-implement", "feature-verify")

// F-001: Built-in platform-pack presets keyed by slug. The full descriptors live in the pure-policy
// table; this projection is the wizard-facing display projection so callers can render a slug ->
// displayName list without depending on internal runtime types.
internal val PLATFORM_PACK_PRESETS: Map<String, String> get() = POLICY_PLATFORM_PACK_PRESETS

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

// SKILL-52.1 subtask 2: canonical implementation now lives in `skillbill.scaffold.policy`.
// This thin alias preserves the historical infra-fs callsite surface.
internal fun displayNameFromSlug(slug: String): String = policyDisplayNameFromSlug(slug)

/**
 * Static lookup of orchestration playbook target paths used by scaffolding when a brand-new skill
 * is created and there is no `pointers:` block to consult yet.
 *
 * AUTHORITATIVE SOURCE: Each platform pack's `platform.yaml` `pointers:` block is the source of
 * truth for which target a pointer file resolves to AT REGENERATION TIME. This map is intentionally
 * kept aligned with those manifests; [validatePointerTargetParity] asserts the agreement at
 * pack-load time so the two sources cannot drift silently.
 *
 * TODO(SKILL-39 follow-up): drive [supportingFileTargets] directly from the matching pack's
 * `pointers:` block (load the manifest, look up by name) so this map can be deleted.
 */
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

/**
 * Returns the install-time support pointer filenames a skill needs. Sourced from the matched
 * class manifest under `orchestration/skill-classes/`. Returns an empty list when the directory
 * is absent (non-governed test fixtures, ad-hoc repos) or no class matches; production repos
 * always carry the directory and the matched class declares the canonical pointer set.
 */
internal fun requiredSupportingFilesForSkill(skillName: String, repoRoot: Path): List<String> {
  if (!Files.isDirectory(repoRoot.resolve(SKILL_CLASSES_DIR))) return emptyList()
  val skillClass = resolveSkillClass(skillName, discoverSkillClasses(repoRoot))
  return skillClass?.pointers?.map { "$it.md" } ?: emptyList()
}

internal fun requireSupportingFileTarget(skillName: String, fileName: String, repoRoot: Path): Path =
  supportingFileTargets(repoRoot)[fileName] ?: throw MissingSupportingFileTargetError(
    "Runtime supporting file '$fileName' is not registered for '$skillName'.",
  )

/**
 * Cross-validates the static [supportingFileTargets] map against every pack's `pointers:` block.
 *
 * For every (pointerName -> target) declared by any pack, the corresponding entry in
 * [supportingFileTargets] (when present) MUST resolve to the same repo-relative path. This is the
 * guard that keeps the legacy static map honest while it still exists.
 *
 * Returns a (sorted) list of issue strings; an empty list means parity holds.
 */
internal fun validatePointerTargetParity(
  repoRoot: Path,
  packs: List<skillbill.scaffold.model.PlatformManifest>,
): List<String> {
  val staticTargets = supportingFileTargets(repoRoot)
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val issues = mutableListOf<String>()
  packs.forEach { pack ->
    pack.pointers.forEach { spec ->
      val staticTarget = staticTargets[spec.name] ?: return@forEach
      val staticAbs = staticTarget.toAbsolutePath().normalize()
      val pointerAbs = resolvedRoot.resolve(spec.target).normalize()
      if (staticAbs != pointerAbs) {
        issues += "platform-packs/${pack.slug}: pointer '${spec.name}' target '${spec.target}' " +
          "disagrees with static supportingFileTargets which points at " +
          "'${runCatching { staticAbs.relativeTo(resolvedRoot) }.getOrDefault(staticAbs)}'"
      }
    }
  }
  return issues.sorted()
}
