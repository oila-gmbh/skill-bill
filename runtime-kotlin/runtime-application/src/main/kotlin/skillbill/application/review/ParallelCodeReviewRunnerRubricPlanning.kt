package skillbill.application.review

import skillbill.application.review.model.ReviewWorkerKind.GENERIC
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.review.plan.ReviewCrossRootLaneReconciliation
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewPerAreaFallbackExclusion
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest

internal class ParallelCodeReviewRunnerRubricPlanning(
  private val reviewRubricResolver: ReviewRubricResolver,
  private val installedPackCatalog: InstalledPlatformPackCatalogPort,
) {
  @Suppress("LongMethod")
  fun resolvePlannedRubrics(
    evidence: ReviewDiffEvidence,
    routedManifests: List<PlatformManifest>,
    manifests: List<PlatformManifest>,
    ownedPathsBySlug: Map<String, Set<String>>,
  ): List<PlannedReviewRubric> = if (routedManifests.isEmpty()) {
    val installed = installedPackCatalog.manifests()
    if (installed.isNotEmpty()) {
      val routing = ReviewStackRouting.route(
        installed,
        evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) },
      )
      if (routing.routedSlugs.isEmpty()) {
        horizontalPlannedRubrics(evidence)
      } else {
        resolvePlannedRubrics(
          evidence,
          installed.filter { it.slug in routing.routedSlugs },
          installed,
          routing.ownedPathsBySlug,
        )
      }
    } else {
      horizontalPlannedRubrics(evidence)
    }
  } else {
    val depthOffsets = ReviewCrossRootLaneReconciliation
      .compositionDepthOffsets(routedManifests.map { it.slug }, manifests)
    val rootLanes = routedManifests.map { root ->
      val rootOwnedPaths = ownedPathsBySlug[root.slug].orEmpty()
      val rootFiles = evidence.files.filter { it.path in rootOwnedPaths }
      val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(root.slug, manifests)
      val lanes = ReviewLaunchPlanPolicy.flatten(root.slug, manifests, selectedAreas).lanes.also { lanes ->
        require(lanes.isNotEmpty()) {
          "Routed pack '${root.slug}' resolved no declared flattened specialist worker."
        }
      }.map { lane ->
        val ownedPaths = if (lane.required) rootOwnedPaths.toList() else laneOwnedPaths(lane, rootFiles)
        lane.copy(
          ownedPaths = ownedPaths.distinct().sorted(),
          changedHunkIds = evidence.hunks.filter { it.path in ownedPaths }.map { it.hunkId },
        )
      }
      ReviewRootLanes(depthOffsets[root.slug] ?: 0, lanes)
    }
    val exclusion = ReviewPerAreaFallbackExclusion.partition(rootLanes, manifests)
    ReviewCrossRootLaneReconciliation
      .reconcile(exclusion.roots, exclusion.excludedFallbackLanesByArea)
      .filter { it.lane.ownedPaths.isNotEmpty() }
      .map { reconciled ->
        val lane = reconciled.lane
        require(
          reconciled.inputs.filter { it.packSlug == lane.packSlug }.all {
            it.area == lane.area && it.skillName == lane.skillName && it.addOns == lane.addOns
          },
        ) {
          "Conflicting ownership for specialist '${lane.skillName}'."
        }
        val owner = manifests.single { it.slug == lane.packSlug }
        val ownedEvidence = evidence.ownedFiles(lane.ownedPaths.toSet()).map {
          ReviewOwnedFileEvidence(it.path, it.changedContent)
        }
        val resolvedOwner = reviewRubricResolver.resolve(owner, ownedEvidence, lane.skillName)
        val resolved = resolvedOwner
          .specialists.singleOrNull { it.area == lane.area }
          ?: resolvedOwner
        PlannedReviewRubric(
          descriptor = lane.copy(addOns = resolved.selectedAddOns),
          rubric = ReviewRubricProjection(lane.skillName, resolved.body, resolved.area ?: lane.area),
          originLayerChains = reconciled.inputs.flatMap { it.originLayerChains }.distinct(),
        )
      }
  }

  private fun horizontalPlannedRubrics(evidence: ReviewDiffEvidence): List<PlannedReviewRubric> {
    val rubric = reviewRubricResolver.resolve(null)
    return listOf(
      PlannedReviewRubric(
        ReviewLaunchLane(
          rubric.rubricId,
          "horizontal",
          rubric.area ?: "generic",
          0,
          listOf("horizontal"),
          true,
          emptyList(),
          0,
          "horizontal base behavior",
          ownedPaths = evidence.hunks.map { it.path }.distinct().sorted(),
          changedHunkIds = evidence.hunks.map { it.hunkId },
        ),
        ReviewRubricProjection(rubric.rubricId, rubric.body, rubric.area),
        workerKind = GENERIC,
      ),
    )
  }

  private fun laneOwnedPaths(lane: ReviewLaunchLane, files: List<ReviewChangedFileEvidence>): List<String> =
    files.filter { file ->
      ReviewLaneInclusionPolicy.ownsChangedFile(lane, file.path, file.changedContent)
    }.map { it.path }
}
