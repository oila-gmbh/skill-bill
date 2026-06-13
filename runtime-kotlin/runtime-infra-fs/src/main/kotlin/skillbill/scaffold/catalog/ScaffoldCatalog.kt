package skillbill.scaffold.catalog

import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.BaselineReviewCompositionEdge
import skillbill.scaffold.model.BaselineReviewLayerSuggestion
import skillbill.scaffold.model.BaselineReviewPackEntry
import skillbill.scaffold.model.BaselineReviewSkillEntry
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.declaredCodeReviewSkillNames
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.platformpack.unsupportedCompositionModeReason
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.runtime.PLATFORM_PACK_PRESETS
import skillbill.scaffold.runtime.PRE_SHELL_FAMILIES
import skillbill.scaffold.runtime.SCAFFOLD_PAYLOAD_VERSION
import skillbill.scaffold.runtime.SHELLED_FAMILIES
import skillbill.scaffold.runtime.scaffold
import java.nio.file.Path

/**
 * Stable, public accessor for scaffold-time option lists used by desktop wizards and other UI
 * surfaces. Keeps the underlying constants `internal` to the scaffold package while exposing a
 * single read-only surface that wizards can render against.
 *
 * Wizard option lists must come from this catalog (or runtime metadata discovered via
 * [discoverPilotedPlatformPacks]). UI code must never duplicate these lists locally — every
 * payload produced by a wizard is validated against the same constants when the scaffolder runs.
 *
 * F-001: Every member delegates to the matching `internal` constant declared in
 * `ScaffoldSupport.kt` (`APPROVED_CODE_REVIEW_AREAS`, `PRE_SHELL_FAMILIES`, `SHELLED_FAMILIES`,
 * `PLATFORM_PACK_PRESETS`, `SCAFFOLD_PAYLOAD_VERSION`) so the runtime stays the single source of
 * truth. There must be no parallel literal copies on this object.
 */
object ScaffoldCatalog {
  /** Approved set of code-review specialist areas; payloads must restrict `area` to this set. */
  val approvedCodeReviewAreas: Set<String>
    get() = APPROVED_CODE_REVIEW_AREAS

  /** Families that have NOT yet been migrated to the shell+content contract. */
  val preShellFamilies: Set<String>
    get() = PRE_SHELL_FAMILIES

  /** Families that are piloted onto the shell+content contract and accept platform overrides. */
  val shelledFamilies: Set<String>
    get() = SHELLED_FAMILIES

  /** Built-in platform-pack presets (slug -> display name). */
  val platformPackPresets: Map<String, String>
    get() = PLATFORM_PACK_PRESETS

  /** Required `scaffold_payload_version` value for SCAFFOLD_PAYLOAD.md v1.0 contract. */
  val scaffoldPayloadVersion: String
    get() = SCAFFOLD_PAYLOAD_VERSION

  /**
   * Discover the set of piloted platform packs available under [packsRoot]. Delegates to the
   * scaffold-package manifest discovery so callers do not need to depend on internal helpers.
   */
  fun discoverPilotedPlatformPacks(packsRoot: Path): List<PlatformManifest> = discoverPlatformPackManifests(packsRoot)

  /**
   * Manifest-driven catalog of code-review baseline layers that desktop authoring can compose
   * into a new platform pack. This is intentionally projected by runtime-core so UI code does not
   * mirror manifest semantics such as declared code-review skill names, composition graph edges,
   * or mode support.
   */
  fun discoverBaselineReviewCatalog(packsRoot: Path): BaselineReviewCatalog {
    val packs = discoverPlatformPackManifests(packsRoot)
    return BaselineReviewCatalog(
      packs = packs
        .filter { pack -> pack.declaredFiles.baseline != null }
        .map { pack ->
          BaselineReviewPackEntry(
            platform = pack.slug,
            displayName = pack.displayName ?: pack.slug,
            strongRoutingSignals = pack.routingSignals.strong,
            skills = pack.declaredCodeReviewSkillNames()
              .sorted()
              .map { skill -> baselineSkillEntry(pack.slug, skill) },
          )
        }
        .sortedBy { pack -> pack.platform },
      compositionEdges = packs.flatMap { pack ->
        pack.codeReviewComposition?.baselineLayers.orEmpty().map { layer ->
          BaselineReviewCompositionEdge(
            sourcePlatform = pack.slug,
            targetPlatform = layer.platform,
            targetSkill = layer.skill,
          )
        }
      }.sortedWith(compareBy({ it.sourcePlatform }, { it.targetPlatform }, { it.targetSkill })),
      layerSuggestions = baselineLayerSuggestions(packs),
    )
  }
}

private const val KOTLIN_BASELINE_PLATFORM = "kotlin"
private const val KOTLIN_BASELINE_SKILL = "bill-kotlin-code-review"
private const val KMP_BASELINE_MODE = "kmp-baseline"
private const val SAME_REVIEW_SCOPE = "same-review-scope"

private val KMP_ANDROID_SUGGESTION_SIGNALS = listOf(
  "kmp",
  "android",
  "com.android",
  "kotlin-multiplatform",
  "multiplatform",
)

private fun baselineLayerSuggestions(packs: List<PlatformManifest>): List<BaselineReviewLayerSuggestion> {
  val kotlinPack = packs.firstOrNull { pack ->
    pack.slug == KOTLIN_BASELINE_PLATFORM &&
      pack.declaredFiles.baseline != null &&
      KOTLIN_BASELINE_SKILL in pack.declaredCodeReviewSkillNames()
  }
  val kotlinSkill = kotlinPack?.let { pack -> baselineSkillEntry(pack.slug, KOTLIN_BASELINE_SKILL) }
  val supportsKmpBaseline = kotlinSkill != null &&
    KMP_BASELINE_MODE in kotlinSkill.supportedModes &&
    SAME_REVIEW_SCOPE in kotlinSkill.supportedScopes
  return if (supportsKmpBaseline) {
    listOf(
      BaselineReviewLayerSuggestion(
        label = "Kotlin baseline",
        triggerSignals = KMP_ANDROID_SUGGESTION_SIGNALS,
        platform = KOTLIN_BASELINE_PLATFORM,
        skill = KOTLIN_BASELINE_SKILL,
        scope = SAME_REVIEW_SCOPE,
        required = true,
        mode = KMP_BASELINE_MODE,
      ),
    )
  } else {
    emptyList()
  }
}

private fun baselineSkillEntry(platform: String, skill: String): BaselineReviewSkillEntry {
  val supportedModes = CodeReviewCompositionMode.entries
    .filter { mode ->
      unsupportedCompositionModeReason(
        CodeReviewBaselineLayer(
          platform = platform,
          skill = skill,
          scope = CodeReviewCompositionScope.SameReviewScope,
          required = true,
          mode = mode,
        ),
      ) == null
    }
    .map { mode -> mode.wireValue }
  val supportedScopes = if (supportedModes.isEmpty()) {
    emptyList()
  } else {
    CodeReviewCompositionScope.entries.map { scope -> scope.wireValue }
  }
  return BaselineReviewSkillEntry(
    name = skill,
    supportedModes = supportedModes,
    supportedScopes = supportedScopes,
  )
}
