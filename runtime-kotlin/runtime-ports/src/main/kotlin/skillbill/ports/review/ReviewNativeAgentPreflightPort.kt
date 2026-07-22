package skillbill.ports.review

import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest

fun interface ReviewNativeAgentPreflightPort {
  fun verify(request: ReviewNativeAgentPreflightRequest)
}
