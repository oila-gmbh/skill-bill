package skillbill.review.plan

import skillbill.review.plan.model.ReviewFallbackExclusionPartition
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.scaffold.model.PlatformManifest

object ReviewPerAreaFallbackExclusion {
  fun partition(
    roots: List<ReviewRootLanes>,
    manifests: Collection<PlatformManifest>,
  ): ReviewFallbackExclusionPartition {
    val fallbackSlug = ReviewFallbackResolver.resolveOptional(manifests.toList())?.slug
      ?: return ReviewFallbackExclusionPartition(sortRoots(roots), emptyMap())
    val sorted = sortRoots(roots)
    val candidates = sorted.flatMap { root -> root.lanes.map { root.depthOffset to it } }
      .sortedWith(
        compareBy<Pair<Int, ReviewLaunchLane>>(
          { it.second.area },
          { it.second.packSlug },
          { it.second.skillName },
          { it.second.depth },
        ),
      )
    val excluded = linkedMapOf<String, ReviewLaunchLane>()
    val removed = mutableSetOf<Triple<String, String, String>>()
    candidates.groupBy { it.second.area }.entries.sortedBy { it.key }.forEach { (area, areaCandidates) ->
      val natives = areaCandidates.filter { it.second.packSlug != fallbackSlug }
      val fallbacks = areaCandidates.filter { it.second.packSlug == fallbackSlug }
      if (natives.isNotEmpty() && fallbacks.isNotEmpty()) {
        excluded[area] = merge(fallbacks.map { it.second })
        fallbacks.forEach { removed += laneIdentity(it.second) }
      }
    }
    val filtered = sorted.map { root ->
      root.copy(
        lanes = root.lanes
          .filter { laneIdentity(it) !in removed }
          .sortedWith(compareBy({ it.area }, { it.packSlug }, { it.skillName })),
      )
    }
    return ReviewFallbackExclusionPartition(filtered, excluded)
  }

  private fun sortRoots(roots: List<ReviewRootLanes>): List<ReviewRootLanes> = roots.sortedWith(
    compareBy(
      { it.depthOffset },
      { root -> root.lanes.minOfOrNull { lane -> lane.packSlug }.orEmpty() },
      { root -> root.lanes.minOfOrNull { lane -> lane.area }.orEmpty() },
    ),
  )

  private fun laneIdentity(lane: ReviewLaunchLane) = Triple(lane.packSlug, lane.area, lane.skillName)

  private fun merge(lanes: List<ReviewLaunchLane>): ReviewLaunchLane {
    val first = lanes.first()
    return first.copy(
      ownedPaths = lanes.flatMap { it.ownedPaths }.distinct().sorted(),
      changedHunkIds = lanes.flatMap { it.changedHunkIds }.distinct(),
      required = lanes.any { it.required },
    )
  }
}
