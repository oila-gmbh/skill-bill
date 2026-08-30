@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package skillbill.scaffold.platformpack

import skillbill.error.InvalidManifestSchemaError
import skillbill.error.MissingContentFileError
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path

internal fun validatePlatformPackCompositions(packs: List<PlatformManifest>) {
  val packsBySlug = packs.associateBy { it.slug }
  packs
    .filter { it.codeReviewComposition != null }
    .forEach { pack -> validateCompositionReferences(pack, packsBySlug) }
  validateNoCompositionCycles(packs)
  packs
    .filter { it.codeReviewComposition != null }
    .forEach(::validateCompositionModeSupport)
  packs
    .filter { it.codeReviewComposition != null }
    .forEach { root ->
      ReviewLaunchPlanPolicy.flatten(
        routedSlug = root.slug,
        manifests = packs,
        selectedAreas = ReviewLaunchPlanPolicy.composedAreas(root.slug, packs),
      )
    }
}

internal fun loadCompositionClosure(rootPack: PlatformManifest): List<PlatformManifest> {
  val packParent = rootPack.packRoot.parent
  return if (packParent == null || !Files.isDirectory(packParent)) {
    listOf(rootPack)
  } else {
    val loaded = linkedMapOf(rootPack.slug to rootPack)

    fun collect(pack: PlatformManifest) {
      pack.codeReviewComposition?.baselineLayers.orEmpty().forEach { layer ->
        if (layer.platform in loaded) {
          return@forEach
        }
        val targetRoot = packParent.resolve(layer.platform)
        if (!Files.isDirectory(targetRoot)) {
          return@forEach
        }
        val targetPack = loadPlatformManifest(targetRoot)
        loaded[targetPack.slug] = targetPack
        collect(targetPack)
      }
    }

    collect(rootPack)
    loaded.values.toList()
  }
}

internal fun validateCompositionReferences(pack: PlatformManifest, packsBySlug: Map<String, PlatformManifest>) {
  val seenTargets = mutableSetOf<Pair<String, String>>()
  pack.codeReviewComposition?.baselineLayers.orEmpty().forEachIndexed { index, layer ->
    val targetLabel = "${layer.platform}/${layer.skill}"
    if (layer.platform == pack.slug) {
      invalidManifestSchema(slug, 
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] self-references " +
          "the same platform pack '$targetLabel'.",
      )
    }
    if (!seenTargets.add(layer.platform to layer.skill)) {
      invalidManifestSchema(slug, 
        "Platform pack '${pack.slug}': duplicate code_review_composition baseline layer '$targetLabel'.",
      )
    }
    val targetPack = packsBySlug[layer.platform]
      ?: invalidManifestSchema(slug, 
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] references " +
          "missing platform pack '${layer.platform}'.",
      )
    if (layer.skill !in targetPack.declaredCodeReviewSkillNames()) {
      invalidManifestSchema(slug, 
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] references " +
          "missing code-review skill '${layer.skill}' in platform pack '${layer.platform}'.",
      )
    }
  }
}

internal fun validateCompositionModeSupport(pack: PlatformManifest) {
  pack.codeReviewComposition?.baselineLayers.orEmpty().forEachIndexed { index, layer ->
    validateCompositionModeSupport(pack.slug, index, layer)
  }
}

internal fun validateCompositionModeSupport(sourceSlug: String, index: Int, layer: CodeReviewBaselineLayer) {
  val unsupportedReason = unsupportedCompositionModeReason(layer)
  if (unsupportedReason != null) {
    invalidManifestSchema(slug, 
      "Platform pack '$sourceSlug': code_review_composition.baseline_layers[$index] uses mode " +
        "'${layer.mode.wireValue}' with unsupported referenced skill '${layer.platform}/${layer.skill}'. " +
        unsupportedReason,
    )
  }
}

internal fun unsupportedCompositionModeReason(layer: CodeReviewBaselineLayer): String? = when (layer.mode) {
  CodeReviewCompositionMode.KmpBaseline ->
    if (layer.skill == "bill-${layer.platform}-code-review") {
      null
    } else {
      "Mode '${layer.mode.wireValue}' requires the referenced pack's baseline code-review skill."
    }
}

internal fun validateNoCompositionCycles(packs: List<PlatformManifest>) {
  val graph: Map<String, List<String>> = packs.associate { pack ->
    pack.slug to pack.codeReviewComposition?.baselineLayers.orEmpty().map { layer -> layer.platform }
  }
  val visited = mutableSetOf<String>()
  val visiting = mutableSetOf<String>()
  val stack = mutableListOf<String>()

  fun visit(slug: String) {
    if (slug in visited) return
    if (slug in visiting) {
      val cycleStart = stack.indexOf(slug).coerceAtLeast(0)
      val cycle = (stack.drop(cycleStart) + slug).joinToString(" -> ")
      invalidManifestSchema(slug, 
        "Platform pack '$slug': code_review_composition contains a composition cycle: $cycle.",
      )
    }

    visiting += slug
    stack += slug
    graph.getValue(slug)
      .filter { target -> target in graph }
      .forEach(::visit)
    stack.removeAt(stack.lastIndex)
    visiting -= slug
    visited += slug
  }

  graph.keys.sorted().forEach(::visit)
}

internal fun PlatformManifest.declaredCodeReviewSkillNames(): Set<String> {
  val names = linkedSetOf<String>()
  routedSkillName?.let(names::add)
  declaredFiles.baseline?.parent?.fileName?.toString()
    ?.takeIf { it != "code-review" }
    ?.let(names::add)
  declaredCodeReviewAreas.forEach { area ->
    names += "bill-$slug-code-review-$area"
    declaredFiles.areas[area]?.parent?.fileName?.toString()
      ?.takeIf { it != "code-review" }
      ?.let(names::add)
  }
  return names
}

internal fun loadQualityCheckContent(pack: PlatformManifest): Path {
  val filePath = pack.declaredQualityCheckFile
    ?: missingManifestContent(
      "Platform pack '${pack.slug}': declared_quality_check_file not set " +
        "(call is only valid after checking pack.declaredQualityCheckFile is not null).",
    )
  validateGovernedSkill(pack, "quality-check", filePath, "quality-check", "")
  return filePath
}
