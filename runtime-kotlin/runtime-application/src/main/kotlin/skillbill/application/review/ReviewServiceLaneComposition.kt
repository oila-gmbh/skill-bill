package skillbill.application.review

import skillbill.error.ShellContentContractException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.ReviewAttributionPort
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.canonicalPackSkillNames
import skillbill.review.canonicalPlatformSlugs
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewRunLane
import skillbill.review.packSlugFromCanonicalPackSkillName
import skillbill.review.plan.model.ReviewLaunchPlan

internal fun composedRunLanes(
  review: ImportedReview,
  reviewAttributionPort: ReviewAttributionPort,
  diagnostics: RuntimeDiagnostics,
): List<ReviewRunLane> {
  val routedPackSlug = packSlugFromCanonicalPackSkillName(review.routedSkillCanonical)
  if (routedPackSlug == null) {
    diagnostics.warning(
      "review lane composition: routed skill '${review.routedSkillCanonical}' names no platform pack; " +
        "run ${review.reviewRunId} imports with unresolved lanes.",
    )
    return ReviewRunLaneResolver.resolve(
      ReviewLaunchPlan(review.routedSkillCanonical, emptyList()),
      review.specialistReviews,
    )
  }
  val plan = try {
    reviewAttributionPort.composedLaunchPlan(routedPackSlug)
  } catch (error: ShellContentContractException) {
    diagnostics.warning(
      "review lane composition: pack '$routedPackSlug' failed to compose " +
        "(${error::class.simpleName}); run ${review.reviewRunId} imports with unresolved lanes " +
        "instead of the composed launch plan.",
      error,
    )
    ReviewLaunchPlan(routedPackSlug, emptyList())
  }
  return ReviewRunLaneResolver.resolve(plan, review.specialistReviews)
}

internal fun canonicalAttributionPorts(reviewAttributionPort: ReviewAttributionPort) =
  reviewAttributionPort.knownPackSkillNames() + canonicalPackSkillNames to
    reviewAttributionPort.knownPlatformSlugs() + canonicalPlatformSlugs
