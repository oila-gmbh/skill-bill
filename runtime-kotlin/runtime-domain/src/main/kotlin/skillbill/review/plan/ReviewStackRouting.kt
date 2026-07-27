package skillbill.review.plan

import skillbill.error.InvalidFallbackCapabilityError
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

    val scores = concreteManifests.associateWith { manifest ->
      val pathScore = changedFiles.sumOf { changed ->
        manifest.routingSignals.path.distinct().sumOf { signal ->
          if (!ReviewPathMatcher.matches(changed.path, signal)) 0
          else if (signalOwners.getValue(signal).size == 1) UNIQUE_PATH_SIGNAL_SCORE else 1
        }
      }
      val contentScore = changedFiles.sumOf { changed ->
        manifest.routingSignals.content.distinct().count { signal ->
          changed.changedContent.contains(signal, ignoreCase = true)
        } * CONTENT_SIGNAL_SCORE
      }
      pathScore to contentScore
    }.filterValues { (pathScore, _) -> pathScore > 0 }

    if (scores.isEmpty()) return fallbackResult(resolveFallback(manifests), changedFiles)
    val strongestPath = scores.values.maxOf { it.first }
    val pathWinners = scores.filterValues { it.first == strongestPath }
    val strongestContent = pathWinners.values.maxOf { it.second }
    val winners = pathWinners.filterValues { it.second == strongestContent }.keys
    val resolved = resolveComposition(winners)
    if (resolved == null) return fallbackResult(resolveFallback(manifests), changedFiles)

    val routedSlugs = linkedSetOf(resolved.slug)
    resolved.codeReviewComposition?.baselineLayers?.mapTo(routedSlugs) { it.platform }
    val ownedPathsBySlug = routedSlugs.associateWith { slug ->
      val manifest = manifests.single { it.slug == slug }
      changedFiles.filter { changed ->
        manifest.routingSignals.path.any { ReviewPathMatcher.matches(changed.path, it) }
      }.mapTo(linkedSetOf()) { it.path }
    }.filterValues { it.isNotEmpty() }
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

  private fun resolveFallback(manifests: List<PlatformManifest>): PlatformManifest {
    val owners = manifests.filter { CODE_REVIEW_CAPABILITY in it.fallbackCapabilities }
    if (owners.size != 1) {
      throw InvalidFallbackCapabilityError(
        "Code-review routing requires exactly one manifest-declared fallback owner; found ${owners.size}.",
      )
    }
    val owner = owners.single()
    if (owner.declaredFiles.baseline == null) {
      throw InvalidFallbackCapabilityError(
        "Platform pack '${owner.slug}' declares the code-review fallback without a code-review baseline.",
      )
    }
    return owner
  }

  private fun fallbackResult(
    fallback: PlatformManifest,
    changedFiles: List<ReviewRoutingChangedFile>,
  ): ReviewStackRoutingResult = ReviewStackRoutingResult(
    setOf(fallback.slug),
    mapOf(fallback.slug to changedFiles.mapTo(linkedSetOf()) { it.path }),
  )

  private const val CODE_REVIEW_CAPABILITY = "code-review"
  private const val UNIQUE_PATH_SIGNAL_SCORE = 10
  private const val CONTENT_SIGNAL_SCORE = 20
}
