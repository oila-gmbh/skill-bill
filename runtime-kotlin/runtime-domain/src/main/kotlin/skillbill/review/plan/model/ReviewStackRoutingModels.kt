package skillbill.review.plan.model

data class ReviewRoutingChangedFile(val path: String, val changedContent: String)

data class ReviewStackRoutingResult(
  val routedSlugs: Set<String>,
  val ownedPathsBySlug: Map<String, Set<String>>,
)
