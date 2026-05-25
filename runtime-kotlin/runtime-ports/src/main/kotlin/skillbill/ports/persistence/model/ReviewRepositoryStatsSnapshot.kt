package skillbill.ports.persistence.model

import skillbill.review.model.ReviewFindingStats

data class ReviewRepositoryStatsSnapshot(
  val reviewRunId: String?,
  val stats: ReviewFindingStats,
)
