package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingRequiredSectionError
import skillbill.ports.scaffold.repo.ScaffoldRepoValidationPort
import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationRequest
import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationResult
import skillbill.scaffold.authoring.AuthoringTarget
import skillbill.scaffold.authoring.validateTarget
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.platformpack.declaredCodeReviewSkillNames
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.platformpack.unsupportedCompositionModeReason
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.runtime.CONTENT_BODY_FILENAME
import skillbill.scaffold.runtime.ScaffoldPlan
import skillbill.scaffold.runtime.displayNameFromSlug
import java.nio.file.Files
import java.nio.file.Path
import skillbill.scaffold.policy.parseBaselineLayerPayload as policyParseBaselineLayerPayload

/**
 * Filesystem adapter for [ScaffoldRepoValidationPort]. Builds the existing
 * `skillbill.scaffold.AuthoringTarget` model from the typed request and delegates to the
 * existing `validateTarget` IO seam in `runtime-infra-fs`. The structured result lets
 * pure-policy callers branch on pass/fail without reading the filesystem themselves.
 *
 * SKILL-52.1 subtask 3 also collected the IO-coupled validators that previously lived at
 * top-level inside `skillbill.scaffold.ScaffoldService.kt` into this adapter:
 *  - [validateBaselineLayerPayloadReferences] (reads `platform.yaml` from each referenced pack)
 *  - [validateScaffold] (post-stage validation that performs `validateTarget` and
 *    `loadPlatformPack` IO)
 *  - [plannedAuthoringTarget] (pure projection retained alongside `validateScaffold` because
 *    it is exclusively consumed by validation callers — keeping it adjacent to its sole
 *    caller preserves cohesion without leaking ownership).
 *  - [optionalBaselineLayers] (parsing + cross-pack validation entrypoint) follows the
 *    validator it consumes.
 *
 * The architecture test `ImplementationOwnershipArchitectureTest` asserts the FQN of those
 * functions resolves to this adapter, not to top-level `skillbill.scaffold`.
 */
@Inject
class FileSystemScaffoldRepoValidation : ScaffoldRepoValidationPort {
  override fun validateAuthoringTarget(
    request: ScaffoldAuthoringValidationRequest,
  ): ScaffoldAuthoringValidationResult {
    val target = AuthoringTarget(
      skillName = request.skillName,
      packageName = request.packageName,
      platform = request.platform,
      displayName = request.displayName,
      family = request.family,
      area = request.area,
      skillFile = request.skillFile,
      contentFile = request.contentFile,
    )
    val issues = validateTarget(target, request.repoRoot)
    return ScaffoldAuthoringValidationResult(issues = issues)
  }

  /**
   * Parses the optional `baseline_layers` payload entry and validates cross-pack
   * references. Returns an empty list when the entry is absent.
   *
   * Replaces the legacy top-level `optionalBaselineLayers` in
   * `skillbill.scaffold.ScaffoldService.kt`.
   */
  internal fun optionalBaselineLayers(
    payload: Map<String, Any?>,
    repoRoot: Path,
    newPlatform: String,
  ): List<CodeReviewBaselineLayer> {
    val raw = payload["baseline_layers"] ?: return emptyList()
    if (raw !is List<*>) {
      failBaselineLayersNotAList()
    }
    if (raw.isEmpty()) {
      failBaselineLayersEmpty()
    }
    val layers = raw.mapIndexed { index, entry -> policyParseBaselineLayerPayload(index, entry) }
    validateBaselineLayerPayloadReferences(layers, repoRoot, newPlatform)
    return layers
  }

  /**
   * Cross-pack reference validator for `baseline_layers` entries. Reads each referenced
   * platform pack's `platform.yaml` to confirm the layer target exists.
   *
   * Replaces the legacy top-level `validateBaselineLayerPayloadReferences` in
   * `skillbill.scaffold.ScaffoldService.kt`.
   */
  internal fun validateBaselineLayerPayloadReferences(
    layers: List<CodeReviewBaselineLayer>,
    repoRoot: Path,
    newPlatform: String,
  ) {
    val seenTargets = mutableSetOf<Pair<String, String>>()
    layers.forEachIndexed { index, layer ->
      val targetLabel = "${layer.platform}/${layer.skill}"
      if (layer.platform == newPlatform) {
        failBaselineSelfReference(index, targetLabel)
      }
      if (!seenTargets.add(layer.platform to layer.skill)) {
        failBaselineDuplicate(targetLabel)
      }
      val targetRoot = repoRoot.resolve("platform-packs").resolve(layer.platform)
      if (!Files.isDirectory(targetRoot) || !Files.isRegularFile(targetRoot.resolve("platform.yaml"))) {
        failBaselineMissingPack(index, layer.platform)
      }
      val targetPack = loadPlatformPack(targetRoot)
      if (layer.skill !in targetPack.declaredCodeReviewSkillNames()) {
        failBaselineMissingSkill(index, layer.platform, layer.skill)
      }
      validateBaselineLayerModeSupport(index, layer)
    }
  }

  /**
   * Post-stage validation. For horizontal skills, asserts the staged authoring target
   * passes `validateTarget`. For other kinds, loads the owning platform pack to confirm
   * the manifest still parses after staging.
   *
   * Replaces the legacy top-level `validateScaffold` in
   * `skillbill.scaffold.ScaffoldService.kt`.
   */
  internal fun validateScaffold(plan: ScaffoldPlan, repoRoot: Path) {
    if (plan.kind == SKILL_KIND_HORIZONTAL) {
      val issues = validateTarget(plannedAuthoringTarget(plan), repoRoot)
      if (issues.isNotEmpty()) {
        failMissingRequiredSection(plan.skillName, issues.first())
      }
      return
    }
    loadPlatformPack(repoRoot.resolve("platform-packs").resolve(plan.platform))
  }

  /**
   * Pure projection from a scaffold plan to its authoring-target view. Kept alongside
   * [validateScaffold] because that function is the sole caller; relocating it here
   * preserves cohesion while complying with AC1's no-top-level-validator rule.
   */
  internal fun plannedAuthoringTarget(plan: ScaffoldPlan): AuthoringTarget = AuthoringTarget(
    skillName = plan.skillName,
    packageName = plan.platform.ifBlank { "base" },
    platform = plan.platform,
    displayName = plan.displayName.ifBlank { displayNameFromSlug(plan.skillName.removePrefix("bill-")) },
    family = plan.family,
    area = plan.area,
    skillFile = plan.skillFile,
    contentFile = plan.contentFile ?: plan.skillPath.resolve(CONTENT_BODY_FILENAME),
  )

  private fun validateBaselineLayerModeSupport(index: Int, layer: CodeReviewBaselineLayer) {
    val unsupportedReason = unsupportedCompositionModeReason(layer)
    if (unsupportedReason != null) {
      failBaselineUnsupportedMode(index, layer, unsupportedReason)
    }
  }
}

private fun failBaselineLayersNotAList(): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'baseline_layers' must be a list of baseline layer objects.",
)

private fun failBaselineLayersEmpty(): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'baseline_layers' must contain at least one layer when provided.",
)

private fun failBaselineSelfReference(index: Int, targetLabel: String): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'baseline_layers[$index]' self-references the new platform pack '$targetLabel'.",
)

private fun failBaselineDuplicate(targetLabel: String): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'baseline_layers' contains duplicate layer '$targetLabel'.",
)

private fun failBaselineMissingPack(index: Int, platform: String): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'baseline_layers[$index]' references missing platform pack '$platform'.",
)

private fun failBaselineMissingSkill(index: Int, platform: String, skill: String): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers[$index]' references missing code-review skill " +
      "'$skill' in platform pack '$platform'.",
  )

private fun failBaselineUnsupportedMode(
  index: Int,
  layer: CodeReviewBaselineLayer,
  unsupportedReason: String,
): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'baseline_layers[$index].mode' uses mode '${layer.mode.wireValue}' with " +
    "unsupported referenced skill '${layer.platform}/${layer.skill}'. $unsupportedReason",
)

private fun failMissingRequiredSection(skillName: String, firstIssue: String): Nothing =
  throw MissingRequiredSectionError("Horizontal skill '$skillName': $firstIssue")
