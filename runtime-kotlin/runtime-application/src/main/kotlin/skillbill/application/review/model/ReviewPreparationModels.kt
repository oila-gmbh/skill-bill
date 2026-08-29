package skillbill.application.review.model

import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.SpecIntentProjection
import java.nio.file.Path

data class ReviewPreparationRequest(
  val reviewId: String,
  val reviewRevision: ReviewRevision,
  val criteriaReferences: Map<String, List<String>> = emptyMap(),
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
  val specIntentProjection: SpecIntentProjection? = null,
  val evidenceStorePath: String? = null,
  val repoRoot: Path? = null,
)

data class ReviewPreparationResult(
  val packet: ReviewContextPacket,
  val assignments: List<ReviewAssignment>,
  val packetEnvelope: ReviewContextEnvelope,
  val assignmentEnvelopes: List<ReviewContextEnvelope>,
)
