package skillbill.ports.review.model

import skillbill.ports.review.ReviewStoredHunkBodyExtractor
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewExpansionRecord
import java.nio.file.Path

data class ReviewEvidenceBrokerBinding(
  val repoRoot: Path,
  val assignment: ReviewAssignment,
  val laneRubricId: String,
  val budget: ReviewContextBudgetPolicy,
  val namedDependencies: Set<String> = emptySet(),
  val trustedExpansionLedger: List<ReviewExpansionRecord> = emptyList(),
  val projectedHunks: List<ReviewChangedHunk> = emptyList(),
  val locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort =
    FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE,
  val bodyExtractor: ReviewStoredHunkBodyExtractor = ReviewStoredHunkBodyExtractor.HUNK_CONTENT,
) {
  init {
    require(laneRubricId.isNotBlank()) { "A bound lane must name the single rubric it owns." }
    require(projectedHunks.map { it.hunkId }.toSet() == assignment.assignedHunks.toSet()) {
      "The evidence broker must receive exactly the assignment-owned projected hunks."
    }
  }
}
