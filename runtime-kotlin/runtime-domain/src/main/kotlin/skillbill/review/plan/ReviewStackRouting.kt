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
    if (manifests.isEmpty()) return ReviewStackRoutingResult(emptySet(), emptyMap())
    val changedFiles = files.filterNot { ReviewPathMatcher.isIgnored(it.path) }
    val signalOwners = manifests.flatMap { manifest ->
      manifest.routingSignals.path.distinct().map { it to manifest.slug }
    }.groupBy({ it.first }, { it.second })

    val routedSlugs = linkedSetOf<String>()
    val ownedPathsBySlug = mutableMapOf<String, MutableSet<String>>()
    changedFiles.forEach { changed ->
      // Path evidence is near-authoritative (a .kt file is a Kotlin file); content signals are
      // broad, language-agnostic tokens ("class ", "import ") that legitimately appear in most
      // stacks' source, so they may only break ties among equally-path-scored manifests and must
      // never let a manifest with no path match at all outrank one with a real path match.
      val scores = manifests.associateWith { manifest ->
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
      }.filterValues { (pathScore, contentScore) -> pathScore > 0 || contentScore > 0 }
      val best = scores.values.maxWithOrNull(compareBy({ it.first }, { it.second })) ?: return@forEach
      val winners = scores.filterValues { it == best }.keys.toMutableList()
      if (winners.size > 1) {
        // With only shared signals, prefer a baseline pack over a composed root. A root wins
        // naturally as soon as one of its manifest-owned KMP/Android/etc. signals matches.
        val composedRoots = winners.filter { it.codeReviewComposition != null }.toSet()
        val baselineSlugs = composedRoots.flatMap { root ->
          root.codeReviewComposition!!.baselineLayers.map { it.platform }
        }.toSet()
        if (baselineSlugs.any { slug -> winners.any { it.slug == slug } }) {
          winners.removeAll(composedRoots)
        }
      }
      winners.forEach { winner ->
        routedSlugs += winner.slug
        ownedPathsBySlug.getOrPut(winner.slug) { mutableSetOf() } += changed.path
      }
    }
    return ReviewStackRoutingResult(routedSlugs, ownedPathsBySlug)
  }

  private const val UNIQUE_PATH_SIGNAL_SCORE = 10
  private const val CONTENT_SIGNAL_SCORE = 20
}
