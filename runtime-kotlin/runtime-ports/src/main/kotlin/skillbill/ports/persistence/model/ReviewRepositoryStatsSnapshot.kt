package skillbill.ports.persistence.model

import skillbill.review.model.ReviewFindingStats
import skillbill.review.model.ReviewHealthStats
import skillbill.review.model.ReviewLaneEffectivenessRow

data class ReviewRepositoryStatsSnapshot(
  val reviewRunId: String?,
  val stats: ReviewFindingStats,
  val health: ReviewHealthStats,
  /** Pack-and-area effectiveness, grouped by canonical routed skill plus lane pack slug and area. */
  val laneEffectiveness: List<ReviewLaneEffectivenessRow> = emptyList(),
)
