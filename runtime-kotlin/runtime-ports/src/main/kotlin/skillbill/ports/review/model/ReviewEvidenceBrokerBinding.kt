package skillbill.ports.review.model

import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewContextBudgetPolicy
import java.nio.file.Path

data class ReviewEvidenceBrokerBinding(
  val repoRoot: Path,
  val assignment: ReviewAssignment,
  val laneRubricId: String,
  val budget: ReviewContextBudgetPolicy,
  val namedDependencies: Set<String> = emptySet(),
) {
  init {
    require(laneRubricId.isNotBlank()) { "A bound lane must name the single rubric it owns." }
  }
}
