package skillbill.review.plan.model

data class ReviewRootLanes(
  val depthOffset: Int,
  val lanes: List<ReviewLaunchLane>,
)

data class ReviewReconciledLane(
  val lane: ReviewLaunchLane,
  val inputs: List<ReviewLaunchLane>,
)
