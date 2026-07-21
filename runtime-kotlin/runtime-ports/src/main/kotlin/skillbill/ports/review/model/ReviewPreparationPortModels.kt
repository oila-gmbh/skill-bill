package skillbill.ports.review.model

import skillbill.ports.review.ReviewBuildTestFactsPort
import skillbill.ports.review.ReviewGuidancePort
import skillbill.ports.review.ReviewLaneSelectionPort
import skillbill.ports.review.ReviewLearningsPort
import skillbill.ports.review.ReviewScopeResolverPort
import skillbill.ports.review.ReviewStackRoutingPort
import skillbill.review.context.model.ReviewChangedHunk

data class ReviewScopeFacts(
  val repositoryIdentity: String,
  val baseRevision: String,
  val headRevision: String,
  val status: String,
  val changedHunks: List<ReviewChangedHunk>,
)

data class ReviewStackRoutingFacts(
  val stack: String?,
  val pack: String?,
  val addOns: List<String>,
  val composedLayers: List<String>,
)

data class ReviewFactPorts(
  val scope: ReviewScopeResolverPort,
  val stackRouting: ReviewStackRoutingPort,
  val guidance: ReviewGuidancePort,
  val learnings: ReviewLearningsPort,
  val buildTestFacts: ReviewBuildTestFactsPort,
  val laneSelection: ReviewLaneSelectionPort,
)
