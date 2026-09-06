package skillbill.application.review

import skillbill.application.review.model.FeatureTaskRuntimeStatsResult
import skillbill.application.review.model.FeatureVerifyStatsResult
import skillbill.application.review.model.GoalStatsResult
import skillbill.application.review.model.ReviewStatsResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.review.ReviewRepository
import skillbill.ports.review.model.ReviewRepositoryStatsSnapshot
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.GoalWorkflowStats

internal fun reviewStatsResult(
  database: DatabaseSessionFactory,
  statsBuilder: (ReviewRepository) -> ReviewRepositoryStatsSnapshot,
): ReviewStatsResult = database.read { unitOfWork ->
  val snapshot = statsBuilder(unitOfWork.reviews)
  ReviewStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    reviewRunId = snapshot.reviewRunId,
    stats = snapshot.stats,
    health = snapshot.health,
    stageMetrics = snapshot.stageMetrics,
    stageMetricsByTier = snapshot.stageMetricsByTier,
  )
}

internal fun featureVerifyStatsResult(
  database: DatabaseSessionFactory,
  statsBuilder: (ReviewRepository) -> FeatureVerifyWorkflowStats,
): FeatureVerifyStatsResult = database.read { unitOfWork ->
  FeatureVerifyStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    stats = statsBuilder(unitOfWork.reviews),
  )
}

internal fun featureTaskRuntimeStatsResult(
  database: DatabaseSessionFactory,
  statsBuilder: (ReviewRepository) -> FeatureTaskRuntimeWorkflowStats,
): FeatureTaskRuntimeStatsResult = database.read { unitOfWork ->
  FeatureTaskRuntimeStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    stats = statsBuilder(unitOfWork.reviews),
  )
}

internal fun goalStatsResult(
  database: DatabaseSessionFactory,
  statsBuilder: (ReviewRepository) -> GoalWorkflowStats,
): GoalStatsResult = database.read { unitOfWork ->
  GoalStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    stats = statsBuilder(unitOfWork.reviews),
  )
}
