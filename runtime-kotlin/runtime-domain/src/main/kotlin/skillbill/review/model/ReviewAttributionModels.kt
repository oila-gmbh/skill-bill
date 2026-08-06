package skillbill.review.model

import skillbill.review.UNRESOLVED_ATTRIBUTION

enum class CanonicalScope(val wireValue: String) {
  WORKING_TREE("working_tree"),
  STAGED("staged"),
  COMMIT_RANGE("commit_range"),
  PULL_REQUEST("pull_request"),
  OTHER("other"),
}

data class CanonicalAttribution(
  val canonical: String,
  val raw: String?,
  val detail: String? = null,
) {
  val resolved: Boolean get() = canonical != UNRESOLVED_ATTRIBUTION
}

sealed class ReviewAttributionResolutionError(message: String) : IllegalArgumentException(message) {
  class MalformedVocabulary(
    val rawValue: String?,
    val vocabulary: String,
    val offendingEntry: String,
  ) : ReviewAttributionResolutionError(
    "Review attribution vocabulary '$vocabulary' contains the malformed entry '$offendingEntry' " +
      "while resolving '${rawValue.orEmpty()}'.",
  )
}
