package skillbill.application.review

import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.ReviewCommitUnit

internal fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

internal fun ReviewBaselineUntrackedPolicy.toEnvelope() = linkedMapOf(
  "included_paths" to includedPaths.sorted(),
  "excluded_paths" to excludedPaths.sorted(),
)

internal fun GovernedReviewLaunch.assignedCommitUnits(): List<ReviewCommitUnit> {
  val unitsBySha = packet.commitUnits.associateBy { it.commitSha }
  return assignment.assignedBundle.entries.map { entry -> unitsBySha.getValue(entry.commitSha) }
}
