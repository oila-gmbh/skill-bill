package skillbill.application.review.model

import skillbill.ports.review.model.ReviewSnapshot

data class ReviewSnapshotPruneResult(
  val liveDbPath: String,
  val confirmed: Boolean,
  val candidates: List<ReviewSnapshot>,
  val deleted: List<ReviewSnapshot>,
) {
  val reclaimedBytes: Long = deleted.sumOf(ReviewSnapshot::sizeBytes)
  val candidateBytes: Long = candidates.sumOf(ReviewSnapshot::sizeBytes)
}
