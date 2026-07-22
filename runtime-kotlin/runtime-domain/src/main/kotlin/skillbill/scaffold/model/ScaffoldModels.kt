package skillbill.scaffold.model

import skillbill.boundary.OpenBoundaryMap
import java.nio.file.Path

data class RoutingSignals(
  val strong: List<String>,
  val tieBreakers: List<String>,
  val path: List<String> = strong,
  val content: List<String> = emptyList(),
)

data class ReviewLaneCondition(
  val required: Boolean = false,
  val path: List<String> = emptyList(),
  val content: List<String> = emptyList(),
) {
  init {
    require(required || path.isNotEmpty() || content.isNotEmpty()) {
      "An optional review lane condition must declare path or content signals."
    }
  }
}

data class DeclaredFiles(
  /**
   * SKILL-47/SKILL-48 contract: `null` means the pack ships no code-review baseline — that is
   * a meaningful, intentional absence at the manifest layer (e.g. quality-check-only packs).
   * Consumers MUST NOT re-narrow this with `!!` or default to a synthesized path; the schema
   * (`orchestration/contracts/platform-pack-schema.yaml`, `declared_files.baseline`) is the
   * single source of truth for the optional/required boundary, and `areas-require-baseline`
   * ensures areas without a baseline already loud-fail upstream.
   */
  val baseline: Path?,
  val areas: Map<String, Path>,
)

data class PointerSpec(
  val skillRelativeDir: String,
  val name: String,
  val target: String,
)

enum class CodeReviewCompositionScope(val wireValue: String) {
  SameReviewScope("same-review-scope"),
  ;

  companion object {
    fun fromWireValue(value: String): CodeReviewCompositionScope? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class CodeReviewCompositionMode(val wireValue: String) {
  KmpBaseline("kmp-baseline"),
  ;

  companion object {
    fun fromWireValue(value: String): CodeReviewCompositionMode? = entries.firstOrNull { it.wireValue == value }
  }
}

data class CodeReviewBaselineLayer(
  val platform: String,
  val skill: String,
  val scope: CodeReviewCompositionScope,
  val required: Boolean,
  val mode: CodeReviewCompositionMode,
)

data class CodeReviewComposition(
  val baselineLayers: List<CodeReviewBaselineLayer>,
)

data class GovernedAddonUsage(
  val skillRelativeDir: String,
  val addons: List<GovernedAddonSelection>,
)

data class FeatureAddonUsage(
  val consumer: String,
  val addons: List<GovernedAddonSelection>,
)

data class GovernedAddonSelection(
  val slug: String,
  val entrypoint: String,
  val companionPointers: List<String> = emptyList(),
  val activation: GovernedAddonActivation? = null,
  val specialistAreas: List<String> = emptyList(),
)

data class GovernedAddonActivation(
  val anyPath: List<String> = emptyList(),
  val anyContent: List<String> = emptyList(),
  val allContent: List<String> = emptyList(),
  val anyOfAllContent: List<List<String>> = emptyList(),
  val excludePath: List<String> = emptyList(),
  val excludeContent: List<String> = emptyList(),
) {
  init {
    require(anyPath.isNotEmpty() || anyContent.isNotEmpty() || allContent.isNotEmpty() || anyOfAllContent.isNotEmpty()) {
      "Add-on activation must declare any, all, or any_of_all signals."
    }
    require((anyPath + anyContent + allContent + anyOfAllContent.flatten() + excludePath + excludeContent)
      .all(String::isNotBlank)) {
      "Add-on activation signals must not be blank."
    }
  }
}

data class PlatformManifest(
  val slug: String,
  val packRoot: Path,
  val contractVersion: String,
  val routingSignals: RoutingSignals,
  val declaredCodeReviewAreas: List<String>,
  val declaredFiles: DeclaredFiles,
  val areaMetadata: Map<String, String>,
  val laneConditions: Map<String, ReviewLaneCondition> = emptyMap(),
  val displayName: String? = null,
  val notes: String? = null,
  val declaredQualityCheckFile: Path? = null,
  val codeReviewComposition: CodeReviewComposition? = null,
  val pointers: List<PointerSpec> = emptyList(),
  val addonUsage: List<GovernedAddonUsage> = emptyList(),
  val featureAddonUsage: List<FeatureAddonUsage> = emptyList(),
  /**
   * SKILL-48 Subtask 3: carries every non-anchored top-level field from `platform.yaml`
   * verbatim. Intentionally untyped (`Map<String, Any?>`) so repo authors can add
   * fork-specific keys to the canonical schema without runtime support being added first;
   * generating Kotlin types for these fields is out of scope.
   */
  @OpenBoundaryMap("Schema custom-field passthrough for platform packs")
  val customFields: Map<String, Any?> = emptyMap(),
) {
  /**
   * SKILL-47/SKILL-48 contract: stable identifier of the pack's code-review baseline shell.
   * `null` means the pack has no code-review feature declared — derived from
   * [DeclaredFiles.baseline] being absent in the manifest. Consumers MUST NOT re-narrow with
   * `!!` or default to a synthesized name to hide that absence; the manifest schema is the
   * single source of truth for whether code-review applies, and downstream features that
   * require it (e.g. agent rendering) must null-check this property.
   */
  val routedSkillName: String? = declaredFiles.baseline?.let { "bill-$slug-code-review" }
}

data class BaselineReviewCatalog(
  val packs: List<BaselineReviewPackEntry>,
  val compositionEdges: List<BaselineReviewCompositionEdge>,
  val layerSuggestions: List<BaselineReviewLayerSuggestion> = emptyList(),
)

data class BaselineReviewPackEntry(
  val platform: String,
  val displayName: String,
  val strongRoutingSignals: List<String>,
  val skills: List<BaselineReviewSkillEntry>,
)

data class BaselineReviewSkillEntry(
  val name: String,
  val supportedModes: List<String>,
  val supportedScopes: List<String>,
)

data class BaselineReviewCompositionEdge(
  val sourcePlatform: String,
  val targetPlatform: String,
  val targetSkill: String,
)

data class BaselineReviewLayerSuggestion(
  val label: String,
  val triggerSignals: List<String>,
  val platform: String,
  val skill: String,
  val scope: String,
  val required: Boolean,
  val mode: String,
)

data class GovernedAddonFile(
  val packSlug: String,
  val addonPath: Path,
) {
  val addonSlug: String = addonPath.fileName.toString().removeSuffix(".md")
}

data class ScaffoldResult(
  val kind: String,
  val skillName: String,
  val skillPath: Path,
  val createdFiles: List<Path> = emptyList(),
  val manifestEdits: List<Path> = emptyList(),
  val manifestPreviews: Map<Path, String> = emptyMap(),
  val symlinks: List<Path> = emptyList(),
  val installTargets: List<Path> = emptyList(),
  val notes: List<String> = emptyList(),
)

/**
 * Match rule for a skill class. The renderer picks the first class file whose matcher list
 * resolves the candidate skill name; within a file, an `exact` match wins over a `pattern` match.
 * `excludeExact` removes skill names that would otherwise match a `pattern`, used when a more
 * specific class wants to opt out of a broader regex declared in another file.
 */
data class SkillClassMatcher(
  val exact: String? = null,
  val pattern: Regex? = null,
  val excludeExact: List<String> = emptyList(),
)

/**
 * Framework-owned section inserted between the generated `## Descriptor` and the authored
 * `## Execution` body. Body is written verbatim, no template substitution.
 */
data class SkillClassSection(
  val heading: String,
  val body: String,
)

/**
 * A single class manifest read from `orchestration/skill-classes/<class>.yaml`. Captures
 * framework behavior for a category of skills (e.g. code-review-shell, quality-check-leaf) so
 * authored `content.md` files can stay free of ceremony and renderer-owned prose.
 */
data class SkillClassManifest(
  val classId: String,
  val classFile: Path,
  val contractVersion: String,
  val matchers: List<SkillClassMatcher>,
  val pointers: List<String>,
  val sections: List<SkillClassSection>,
  val ceremonyLines: List<String>,
)
