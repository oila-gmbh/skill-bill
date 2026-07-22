package skillbill.application.review

import skillbill.review.plan.ReviewPathMatcher

internal object RoutingSignalPathMatcher {
  fun matches(path: String, signal: String): Boolean {
    return ReviewPathMatcher.matches(path, signal)
  }

  fun isIgnored(path: String): Boolean {
    return ReviewPathMatcher.isIgnored(path)
  }
}
