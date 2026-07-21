package skillbill.ports.review

import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy

fun interface ReviewLaunchIsolationResolver {
  fun isolationFor(agentId: String): ReviewLaunchIsolationStrategy
}
