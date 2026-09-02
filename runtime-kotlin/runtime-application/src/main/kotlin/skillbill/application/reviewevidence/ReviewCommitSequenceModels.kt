package skillbill.application.reviewevidence

import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitUnit

internal data class ResolvedCommitSequence(
  val units: List<ReviewCommitUnit>,
  val coverageFact: ReviewCommitCoverageFact,
)

internal data class ReviewCommitRange(val baseRevision: String, val headRevision: String) {
  val span: String get() = "$baseRevision..$headRevision"
}
