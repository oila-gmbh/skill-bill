package skillbill.ports.review.model

import skillbill.ports.review.ReviewBuildTestFactsPort
import skillbill.ports.review.ReviewGuidancePort
import skillbill.ports.review.ReviewLaneSelectionPort
import skillbill.ports.review.ReviewLearningsPort
import skillbill.ports.review.ReviewScopeResolverPort
import skillbill.ports.review.ReviewStackRoutingPort
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewLaneDecision

data class ReviewScopeFacts(
  val repositoryIdentity: String,
  val baseRevision: String,
  val headRevision: String,
  val status: String,
  val changedHunks: List<ReviewChangedHunk>,
  val commitUnits: List<ReviewCommitUnit>,
  val coverageFact: ReviewCommitCoverageFact,
)

/** Lane selection and the commit/lane routing that produced it travel together; one decides the other. */
data class ReviewLaneSelection(
  val decisions: List<ReviewLaneDecision>,
  val routingMatrix: ReviewCommitLaneRoutingMatrix,
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
