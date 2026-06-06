package skillbill.ports.persistence.model

import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewHealthStats

data class ReviewRepositoryStatsSnapshot(
  val reviewRunId: String?,
  val stats: ReviewFindingStats,
  val health: ReviewHealthStats,
)
