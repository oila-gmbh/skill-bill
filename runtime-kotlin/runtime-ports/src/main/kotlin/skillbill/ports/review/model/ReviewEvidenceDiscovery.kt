package skillbill.ports.review.model

import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewEvidenceLimits

const val REVIEW_DISCOVERY_PAGE_SIZE: Int = 32
const val REVIEW_DISCOVERY_MAX_BYTES: Int = 32 * 1024
const val REVIEW_EVIDENCE_BATCH_SIZE: Int = 32
const val REVIEW_EVIDENCE_MAX_REQUESTS: Int = 4096

data class ReviewEvidenceOwner(
  val lane: String,
  val assignmentDigest: String,
  val rubricId: String,
  val unitId: String,
) {
  init {
    listOf(lane, rubricId, unitId).forEach(ReviewEvidenceLimits::field)
    if (listOf(lane, rubricId, unitId).any(String::isBlank) || !assignmentDigest.matches(Regex("[a-f0-9]{64}"))) {
      throw InvalidReviewContextSchemaError(
        "review-evidence-owner",
        "Evidence ownership must retain complete provenance.",
      )
    }
  }
}

data class ReviewEvidenceSource(
  val assignment: ReviewAssignment,
  val rubricId: String,
  val namedDependencies: Set<String> = emptySet(),
  val coordinates: ReviewEvidenceCoordinates = ReviewEvidenceCoordinates.Committed(assignment.headRevision),
)

data class ReviewEvidenceDiscoveryRequest(val cursor: String? = null, val pageSize: Int = REVIEW_DISCOVERY_PAGE_SIZE)

data class ReviewEvidenceCatalogEntry(
  val selector: String,
  val path: String,
  val owners: List<ReviewEvidenceOwner>,
  val expansionId: String? = null,
) {
  init {
    listOfNotNull(selector, path, expansionId).forEach(ReviewEvidenceLimits::field)
  }
}

data class ReviewEvidenceDiscoveryPage(
  val assignmentDigest: String,
  val entries: List<ReviewEvidenceCatalogEntry>,
  val nextCursor: String?,
)
