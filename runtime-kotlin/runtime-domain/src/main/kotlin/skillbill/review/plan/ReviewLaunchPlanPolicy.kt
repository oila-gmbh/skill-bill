package skillbill.review.plan

import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.scaffold.model.PlatformManifest

object ReviewLaunchPlanPolicy {
  fun composedAreas(routedSlug: String, manifests: Collection<PlatformManifest>): Set<String> =
    composeReviewLaunchAreas(routedSlug, manifests)

  fun flatten(
    routedSlug: String,
    manifests: Collection<PlatformManifest>,
    selectedAreas: Set<String>,
  ): ReviewLaunchPlan = flattenReviewLaunchPlan(routedSlug, manifests, selectedAreas)
}
