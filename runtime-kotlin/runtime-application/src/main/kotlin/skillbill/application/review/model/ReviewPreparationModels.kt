package skillbill.application.review.model

import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewRevision

data class ReviewPreparationRequest(
  val reviewId: String,
  val reviewRevision: ReviewRevision,
  val criteriaReferences: Map<String, List<String>> = emptyMap(),
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
  val specIntentProjection: skillbill.domain.review.context.model.SpecIntentProjection? = null,
)

data class ReviewPreparationResult(
  val packet: ReviewContextPacket,
  val assignments: List<ReviewAssignment>,
  val packetEnvelope: ReviewContextEnvelope,
  val assignmentEnvelopes: List<ReviewContextEnvelope>,
)
