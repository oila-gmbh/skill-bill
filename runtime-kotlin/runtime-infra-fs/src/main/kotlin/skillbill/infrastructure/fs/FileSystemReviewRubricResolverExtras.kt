package skillbill.infrastructure.fs

import skillbill.review.plan.ReviewContentMatcher
import skillbill.review.plan.ReviewPathMatcher
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.ports.review.model.ReviewOwnedFileEvidence

internal fun governedAddonSelectionMatches(
  selection: GovernedAddonSelection,
  evidence: List<ReviewOwnedFileEvidence>,
): Boolean {
  val condition = requireNotNull(selection.activation) {
    "Governed review add-on '${selection.slug}' has no structured activation."
  }
  val eligible = evidence.filterNot { file ->
    condition.excludePath.any { ReviewPathMatcher.matches(file.path, it) } ||
      condition.excludeContent.any { ReviewContentMatcher.contains(file.changedContent, it) }
  }
  val eligibleContent = eligible.joinToString("\n") { it.changedContent }
  return eligible.isNotEmpty() &&
    ReviewContentMatcher.containsAll(eligibleContent, condition.allContent) &&
    governedAddonActivationMatches(condition, eligible, eligibleContent)
}

private fun governedAddonActivationMatches(
  condition: GovernedAddonActivation,
  eligible: List<ReviewOwnedFileEvidence>,
  eligibleContent: String,
): Boolean {
  if (condition.anyPath.any { signal -> eligible.any { ReviewPathMatcher.matches(it.path, signal) } }) {
    return true
  }
  if (condition.anyContent.any { ReviewContentMatcher.contains(eligibleContent, it) }) {
    return true
  }
  if (condition.anyOfAllContent.any { group -> ReviewContentMatcher.containsAll(eligibleContent, group) }) {
    return true
  }
  return condition.anyPath.isEmpty() && condition.anyContent.isEmpty() && condition.anyOfAllContent.isEmpty()
}
