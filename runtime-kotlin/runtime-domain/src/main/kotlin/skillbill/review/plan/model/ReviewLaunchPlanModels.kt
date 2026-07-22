package skillbill.review.plan.model

data class ReviewLaunchLane(
  val skillName: String,
  val packSlug: String,
  val area: String,
  val depth: Int,
  val originLayerChain: List<String>,
  val required: Boolean,
  val addOns: List<String>,
  val orderIndex: Int,
  val inclusionReason: String,
  val pathSignals: List<String> = emptyList(),
  val contentSignals: List<String> = emptyList(),
) {
  init {
    require(originLayerChain.isNotEmpty()) { "A review launch lane must retain its composition attribution." }
    require(originLayerChain.last() == packSlug) { "A review launch lane attribution must end at its owner." }
  }
}

data class ReviewLaunchPlan(
  val routedPackSlug: String,
  val lanes: List<ReviewLaunchLane>,
) {
  init {
    require(lanes.map { it.packSlug to it.area }.distinct().size == lanes.size) {
      "A review launch plan must not contain duplicate pack/area assignments."
    }
    require(lanes.map { it.orderIndex } == lanes.indices.toList()) {
      "A review launch plan must use contiguous ascending order indexes."
    }
  }
}
