package skillbill.review.plan.model

data class ReviewFallbackExclusionPartition(
  val roots: List<ReviewRootLanes>,
  val excludedFallbackLanesByArea: Map<String, ReviewLaunchLane>,
)
