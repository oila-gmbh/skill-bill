package skillbill.ports.review

import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLearningsReference
import skillbill.review.context.model.ReviewRuleReference

interface ReviewScopeResolverPort {
  fun resolveScope(reviewId: String): ReviewScopeFacts
}

interface ReviewStackRoutingPort {
  fun resolveStackRouting(scope: ReviewScopeFacts): ReviewStackRoutingFacts
}

interface ReviewGuidancePort {
  fun resolveMatchedRules(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts): List<ReviewRuleReference>
}

interface ReviewLearningsPort {
  fun resolveLearnings(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts): List<ReviewLearningsReference>
}

interface ReviewBuildTestFactsPort {
  fun resolveBuildTestFacts(scope: ReviewScopeFacts): List<ReviewBuildTestFact>
}

interface ReviewLaneSelectionPort {
  fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts): List<ReviewLaneDecision>
}
