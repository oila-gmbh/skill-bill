package skillbill.review.plan

import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.review.plan.model.ReviewStackRoutingResult
import skillbill.scaffold.model.PlatformManifest

/**
 * Single authority for mapping changed files to the platform packs a review routes to.
 *
 * The review launch seam and the review-phase native-agent preflight gate both route through this
 * object. A second, weaker routing approximation on either path is a defect: the preflight would
 * then demand specialists for packs the review never launches.
 */
object ReviewStackRouting {
  /** Path-only routing for genuinely unavailable content, such as a deleted file. */
  fun routeByPath(manifests: List<PlatformManifest>, paths: List<String>): ReviewStackRoutingResult =
    route(manifests, paths.map { ReviewRoutingChangedFile(it, "") })

  fun route(manifests: List<PlatformManifest>, files: List<ReviewRoutingChangedFile>): ReviewStackRoutingResult {
    val changedFiles = files.filterNot { ReviewPathMatcher.isIgnored(it.path) }
    val concreteManifests = manifests.filterNot { CODE_REVIEW_CAPABILITY in it.fallbackCapabilities }
    val signalOwners = concreteManifests.flatMap { manifest ->
      manifest.routingSignals.path.distinct().map { it to manifest.slug }
    }.groupBy({ it.first }, { it.second })

    val fallback by lazy { ReviewFallbackResolver.resolveOptional(manifests) }
    if (changedFiles.isEmpty()) {
      return ReviewStackRoutingResult(emptySet(), emptyMap())
    }
    val routedSlugs = linkedSetOf<String>()
    val ownedPathsBySlug = linkedMapOf<String, LinkedHashSet<String>>()

    changedFiles.forEach { changed ->
      val scores = concreteManifests.associateWith { manifest ->
        val pathScore = manifest.routingSignals.path.distinct().sumOf { signal ->
          if (!ReviewPathMatcher.matches(changed.path, signal)) {
            0
          } else if (signalOwners.getValue(signal).size == 1) {
            UNIQUE_PATH_SIGNAL_SCORE
          } else {
            1
          }
        }
        val contentScore = manifest.routingSignals.content.distinct().count { signal ->
          changed.changedContent.contains(signal, ignoreCase = true)
        } * CONTENT_SIGNAL_SCORE
        pathScore to contentScore
      }.filterValues { (pathScore, _) -> pathScore > 0 }

      val resolved = scores.takeIf { it.isNotEmpty() }?.let {
        val strongestPath = it.values.maxOf { score -> score.first }
        val pathWinners = it.filterValues { score -> score.first == strongestPath }
        val strongestContent = pathWinners.values.maxOf { score -> score.second }
        resolveComposition(pathWinners.filterValues { score -> score.second == strongestContent }.keys)
      }
      val owners = if (resolved == null) {
        fallback?.let { setOf(it.slug) }.orEmpty()
      } else {
        linkedSetOf(resolved.slug).apply {
          resolved.codeReviewComposition?.baselineLayers?.mapTo(this) { it.platform }
        }
      }
      owners.forEach { slug ->
        routedSlugs += slug
        ownedPathsBySlug.getOrPut(slug, ::linkedSetOf) += changed.path
      }
    }

    return ReviewStackRoutingResult(routedSlugs, ownedPathsBySlug)
  }

  private fun resolveComposition(winners: Set<PlatformManifest>): PlatformManifest? {
    if (winners.size == 1) return winners.single()
    val survivors = winners.filterNot { candidate ->
      candidate.codeReviewComposition?.baselineLayers?.any { baseline ->
        winners.any { it.slug == baseline.platform }
      } == true
    }
    return survivors.singleOrNull()
  }

  private const val CODE_REVIEW_CAPABILITY = "code-review"
  private const val UNIQUE_PATH_SIGNAL_SCORE = 10
  private const val CONTENT_SIGNAL_SCORE = 20
}
