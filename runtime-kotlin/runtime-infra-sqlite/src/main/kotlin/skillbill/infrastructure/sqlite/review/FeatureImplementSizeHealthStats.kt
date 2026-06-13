package skillbill.infrastructure.sqlite.review

import skillbill.db.telemetry.durationSeconds
import skillbill.review.model.FeatureSizeOutcomeStats
import skillbill.review.model.LargeFeatureHealthStats

private const val LARGE_FEATURE_UNHEALTHY_RECOMMENDATION_THRESHOLD = 0.001

fun buildFeatureSizeOutcomeStats(productionRows: List<Map<String, Any?>>): Map<String, FeatureSizeOutcomeStats> =
  listOf("SMALL", "MEDIUM", "LARGE").associateWith { featureSize ->
    val sizeRows = productionRows.filter { it.stringValue("feature_size") == featureSize }
    buildSingleFeatureSizeOutcomeStats(sizeRows)
  }

fun buildLargeFeatureHealthStats(
  featureSizeOutcomeStats: Map<String, FeatureSizeOutcomeStats>,
): LargeFeatureHealthStats {
  val large = featureSizeOutcomeStats.getValue("LARGE")
  val unhealthyRuns =
    large.abandonedAtPlanningRuns + large.abandonedAtImplementationRuns + large.abandonedAtReviewRuns + large.errorRuns
  val overallDenominator = featureSizeOutcomeStats.values.sumOf { it.totalRuns }
  val overallUnhealthyRuns = featureSizeOutcomeStats.values.sumOf(::unhealthyRuns)
  val unhealthyRate = rate(unhealthyRuns, large.totalRuns)
  val overallUnhealthyRate = rate(overallUnhealthyRuns, overallDenominator)
  return LargeFeatureHealthStats(
    denominatorRuns = large.totalRuns,
    completedRuns = large.completedRuns,
    abandonedRuns = large.abandonedAtPlanningRuns + large.abandonedAtImplementationRuns + large.abandonedAtReviewRuns,
    errorRuns = large.errorRuns,
    unhealthyRuns = unhealthyRuns,
    unhealthyRate = unhealthyRate,
    overallUnhealthyRate = overallUnhealthyRate,
    recommendationThreshold = LARGE_FEATURE_UNHEALTHY_RECOMMENDATION_THRESHOLD,
    recommendation = largeFeatureRecommendation(large.totalRuns, unhealthyRate, overallUnhealthyRate),
  )
}

private fun buildSingleFeatureSizeOutcomeStats(sizeRows: List<Map<String, Any?>>): FeatureSizeOutcomeStats {
  val sizeFinishedRows = finishedRows(sizeRows)
  val normalDurations = sizeFinishedRows.map(::durationSeconds).filter(::isNormalFeatureImplementDuration)
  return FeatureSizeOutcomeStats(
    totalRuns = sizeRows.size,
    completedRuns = sizeFinishedRows.countStatus("completed"),
    completedRate = rate(sizeFinishedRows.countStatus("completed"), sizeRows.size),
    abandonedAtPlanningRuns = sizeFinishedRows.countStatus("abandoned_at_planning"),
    abandonedAtPlanningRate = rate(sizeFinishedRows.countStatus("abandoned_at_planning"), sizeRows.size),
    abandonedAtImplementationRuns = sizeFinishedRows.countStatus("abandoned_at_implementation"),
    abandonedAtImplementationRate = rate(sizeFinishedRows.countStatus("abandoned_at_implementation"), sizeRows.size),
    abandonedAtReviewRuns = sizeFinishedRows.countStatus("abandoned_at_review"),
    abandonedAtReviewRate = rate(sizeFinishedRows.countStatus("abandoned_at_review"), sizeRows.size),
    errorRuns = sizeFinishedRows.countStatus("error"),
    errorRate = rate(sizeFinishedRows.countStatus("error"), sizeRows.size),
    openRuns = sizeRows.size - sizeFinishedRows.size,
    averageDurationSeconds = average(normalDurations),
    medianDurationSeconds = median(normalDurations),
    p90DurationSeconds = p90(normalDurations),
  )
}

private fun unhealthyRuns(stats: FeatureSizeOutcomeStats): Int =
  stats.abandonedAtPlanningRuns + stats.abandonedAtImplementationRuns + stats.abandonedAtReviewRuns + stats.errorRuns

private fun largeFeatureRecommendation(
  denominatorRuns: Int,
  unhealthyRate: Double,
  overallUnhealthyRate: Double,
): String = if (
  denominatorRuns > 0 &&
  unhealthyRate >= LARGE_FEATURE_UNHEALTHY_RECOMMENDATION_THRESHOLD &&
  unhealthyRate >= overallUnhealthyRate
) {
  "Decompose large features or block earlier before implementation when LARGE runs show abandonment or errors."
} else {
  "No large-feature decomposition recommendation."
}
