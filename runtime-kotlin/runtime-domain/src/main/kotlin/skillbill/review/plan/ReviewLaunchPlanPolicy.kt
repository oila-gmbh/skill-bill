package skillbill.review.plan

import skillbill.error.AmbiguousLaneOwnershipError
import skillbill.error.IncompatibleCompositionContractError
import skillbill.error.MissingCompositionLayerError
import skillbill.error.ReviewCompositionCycleError
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.scaffold.model.PlatformManifest

object ReviewLaunchPlanPolicy {
  @Suppress("CyclomaticComplexMethod")
  fun flatten(
    routedSlug: String,
    manifests: Collection<PlatformManifest>,
    selectedAreas: Set<String>,
  ): ReviewLaunchPlan {
    if (selectedAreas.isEmpty()) return ReviewLaunchPlan(routedSlug, emptyList())
    val bySlug = manifests.associateBy { it.slug }
    val root = bySlug[routedSlug]
      ?: throw MissingCompositionLayerError("Routed platform pack '$routedSlug' is missing from review composition.")
    val candidates = mutableListOf<AreaCandidate>()

    @Suppress("ThrowsCount")
    fun visit(pack: PlatformManifest, depth: Int, chain: List<String>, path: List<String>, required: Boolean) {
      if (pack.slug in path) {
        val cycle = (path.dropWhile { it != pack.slug } + pack.slug).joinToString(" -> ")
        throw ReviewCompositionCycleError("Review composition contains a cycle: $cycle.")
      }
      pack.declaredCodeReviewAreas.filter { it in selectedAreas }.forEach { area ->
        candidates += AreaCandidate(pack, area, depth, chain + pack.slug, required)
      }
      pack.codeReviewComposition?.baselineLayers.orEmpty().forEach { layer ->
        val target = bySlug[layer.platform]
          ?: throw MissingCompositionLayerError(
            "Platform pack '${pack.slug}' references missing composition layer '${layer.platform}/${layer.skill}'.",
          )
        if (target.contractVersion != root.contractVersion) {
          throw IncompatibleCompositionContractError(
            "Composition layer '${target.slug}' uses contract '${target.contractVersion}', " +
              "but routed pack '${root.slug}' uses '${root.contractVersion}'.",
          )
        }
        if (layer.skill != target.routedSkillName) {
          throw MissingCompositionLayerError(
            "Platform pack '${pack.slug}' references unavailable baseline skill '${layer.platform}/${layer.skill}'.",
          )
        }
        visit(target, depth + 1, chain + pack.slug, path + pack.slug, required || layer.required)
      }
    }

    visit(root, 0, emptyList(), emptyList(), false)
    val winners = selectedAreas.sorted().mapNotNull { area ->
      val areaCandidates = candidates.filter { it.area == area }
      val nearestDepth = areaCandidates.minOfOrNull { it.depth } ?: return@mapNotNull null
      val nearest = areaCandidates.filter { it.depth == nearestDepth }
      val owners = nearest.groupBy { it.pack.slug }
      if (owners.size > 1) {
        throw AmbiguousLaneOwnershipError(
          "Review area '$area' has ambiguous ownership at composition depth $nearestDepth: " +
            owners.keys.sorted().joinToString() + ".",
        )
      }
      val variants = owners.values.single()
      variants.first().copy(
        chains = variants.flatMap { it.chains }.distinct(),
        required = variants.any { it.required },
      )
    }.sortedWith(compareBy<AreaCandidate>({ it.depth }, { it.pack.slug }, { it.area }))

    return ReviewLaunchPlan(
      routedPackSlug = routedSlug,
      lanes = winners.mapIndexed { index, winner ->
        val skillName = "bill-${winner.pack.slug}-code-review-${winner.area}"
        val consumer = "code-review/$skillName"
        val condition = winner.pack.laneConditions[winner.area]
        ReviewLaunchLane(
          skillName = skillName,
          packSlug = winner.pack.slug,
          area = winner.area,
          depth = winner.depth,
          originLayerChain = winner.chain,
          originLayerChains = winner.chains,
          required = winner.required || condition?.required == true,
          addOns = winner.pack.addonUsage.firstOrNull { it.skillRelativeDir == consumer }
            ?.addons.orEmpty().map { it.slug },
          orderIndex = index,
          inclusionReason = if (winner.depth == 0) "routed-pack override" else "required baseline layer",
          pathSignals = condition?.path.orEmpty(),
          contentSignals = condition?.content.orEmpty(),
        )
      },
    )
  }

  private data class AreaCandidate(
    val pack: PlatformManifest,
    val area: String,
    val depth: Int,
    val chain: List<String>,
    val required: Boolean,
    val chains: List<List<String>> = listOf(chain),
  )
}
