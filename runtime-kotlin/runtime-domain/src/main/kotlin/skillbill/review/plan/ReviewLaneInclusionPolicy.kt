package skillbill.review.plan

import skillbill.review.plan.model.ReviewLaunchLane

object ReviewLaneInclusionPolicy {
  fun ownsChangedFile(lane: ReviewLaunchLane, path: String, changedContent: String): Boolean {
    return lane.required ||
      lane.pathSignals.any { ReviewPathMatcher.matches(path, it) } ||
      lane.contentSignals.any { ReviewContentMatcher.contains(changedContent, it) }
  }
}
