package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import java.sql.Connection

private val featureVerifyCompletionStatuses =
  listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error")
private val auditResults = listOf("all_pass", "had_gaps", "skipped", "not_reached")
private val historySignalValues = listOf("none", "irrelevant", "low", "medium", "high")
private val featureSizes = listOf("SMALL", "MEDIUM", "LARGE")
private val completionStatuses =
  listOf(
    "completed",
    "abandoned_at_planning",
    "abandoned_at_implementation",
    "abandoned_at_review",
    "error",
    "stale",
  )
private val validationResults = listOf("pass", "fail", "skipped", "not_reached")
private val featureFlagPatterns = listOf("simple_conditional", "di_switch", "legacy", "none")
private val featureTaskRuntimeCompletionStatuses =
  listOf("completed", "blocked", "decomposed_at_planning", "error")
private val featureTaskRuntimePhaseOutcomes = listOf("completed", "blocked", "running")

fun buildFeatureTaskRuntimeStats(rows: List<Map<String, Any?>>): FeatureTaskRuntimeWorkflowStats {
  val finishedRows = finishedRows(rows)
  val completedRuns = finishedRows.count { it.stringValue("completion_status") == "completed" }
  val blockedRuns = finishedRows.count { it.stringValue("completion_status") == "blocked" }
  val decomposedRuns = finishedRows.count { it.stringValue("completion_status") == "decomposed_at_planning" }
  val errorRuns = finishedRows.count { it.stringValue("completion_status") == "error" }
  val completedPhaseCounts = finishedRows.map { parseJsonList(it["completed_phase_ids"]).size }
  return FeatureTaskRuntimeWorkflowStats(
    totalRuns = rows.size,
    finishedRuns = finishedRows.size,
    inProgressRuns = rows.size - finishedRows.size,
    featureSizeCounts = countValues(rows, "feature_size", featureSizes),
    completionStatusCounts = countValues(finishedRows, "completion_status", featureTaskRuntimeCompletionStatuses),
    phaseOutcomeCounts = phaseOutcomeCounts(finishedRows),
    completedRuns = completedRuns,
    completedRate = rate(completedRuns, finishedRows.size),
    blockedRuns = blockedRuns,
    blockedRate = rate(blockedRuns, finishedRows.size),
    decomposedRuns = decomposedRuns,
    decomposedRate = rate(decomposedRuns, finishedRows.size),
    errorRuns = errorRuns,
    errorRate = rate(errorRuns, finishedRows.size),
    averageCompletedPhaseCount = average(completedPhaseCounts),
  )
}

private fun phaseOutcomeCounts(rows: List<Map<String, Any?>>): Map<String, Int> {
  val counts = featureTaskRuntimePhaseOutcomes.associateWith { 0 }.toMutableMap()
  rows.forEach { row ->
    val outcomes = JsonSupport.parseObjectOrNull(row.stringValue("phase_outcomes"))
      ?.let { JsonSupport.jsonElementToValue(it) as? Map<*, *> }
      .orEmpty()
    outcomes.values.forEach { status ->
      val key = status?.toString().orEmpty()
      if (key in counts) {
        counts[key] = counts.getValue(key) + 1
      }
    }
  }
  return counts
}

fun buildFeatureVerifyStats(rows: List<Map<String, Any?>>): FeatureVerifyWorkflowStats {
  val finishedRows = finishedRows(rows)
  val rolloutRelevantRuns = rows.count { it.booleanValue("rollout_relevant") }
  val auditPerformedRuns = finishedRows.count { it.booleanValue("feature_flag_audit_performed") }
  val historyReadRuns = finishedRows.count(::historySignalsPresent)
  val historyRelevantRuns = finishedRows.count { it.stringValue("history_relevance") in setOf("medium", "high") }
  val historyHelpfulRuns = finishedRows.count { it.stringValue("history_helpfulness") in setOf("medium", "high") }
  val runsWithGapsFound = finishedRows.count { parseJsonList(it["gaps_found"]).isNotEmpty() }
  val reviewIterations = finishedRows.mapNotNull { it.intValue("review_iterations") }
  val durations = finishedRows.map(::durationSeconds).filter { it > 0 }
  val acceptanceCriteriaCounts = rows.mapNotNull { it.intValue("acceptance_criteria_count") }
  return FeatureVerifyWorkflowStats(
    totalRuns = rows.size,
    finishedRuns = finishedRows.size,
    inProgressRuns = rows.size - finishedRows.size,
    completionStatusCounts = countValues(finishedRows, "completion_status", featureVerifyCompletionStatuses),
    auditResultCounts = countValues(finishedRows, "audit_result", auditResults),
    rolloutRelevantRuns = rolloutRelevantRuns,
    rolloutRelevantRate = rate(rolloutRelevantRuns, rows.size),
    featureFlagAuditPerformedRuns = auditPerformedRuns,
    featureFlagAuditPerformedRate = rate(auditPerformedRuns, finishedRows.size),
    historyReadRuns = historyReadRuns,
    historyReadRate = rate(historyReadRuns, finishedRows.size),
    historyRelevantRuns = historyRelevantRuns,
    historyRelevantRate = rate(historyRelevantRuns, finishedRows.size),
    historyHelpfulRuns = historyHelpfulRuns,
    historyHelpfulRate = rate(historyHelpfulRuns, finishedRows.size),
    historyRelevanceCounts = countValues(finishedRows, "history_relevance", historySignalValues),
    historyHelpfulnessCounts = countValues(finishedRows, "history_helpfulness", historySignalValues),
    runsWithGapsFound = runsWithGapsFound,
    averageAcceptanceCriteriaCount = average(acceptanceCriteriaCounts),
    averageReviewIterations = average(reviewIterations),
    averageDurationSeconds = average(durations),
  )
}

fun buildFeatureImplementStats(rows: List<Map<String, Any?>>): FeatureImplementWorkflowStats {
  val finishedRows = finishedRows(rows)
  val healthStats = buildFeatureImplementHealthStats(rows, finishedRows)
  val averages = buildFeatureImplementAverageStats(rows, finishedRows, healthStats.normalDurations)
  return FeatureImplementWorkflowStats(
    totalRuns = rows.size,
    finishedRuns = finishedRows.size,
    inProgressRuns = rows.size - finishedRows.size,
    rawRunCount = rows.size,
    sourceCounts = healthStats.sourceCounts,
    validHealthDenominatorRuns = healthStats.validHealthDenominatorRuns,
    dataQualityDebtRuns = healthStats.dataQualityDebtRuns,
    malformedSessionIdRuns = healthStats.malformedSessionIdRuns,
    unknownSourceRuns = healthStats.unknownSourceRuns,
    duplicateTerminalFinishedEvents = healthStats.duplicateTerminalFinishedEvents,
    openRuns = healthStats.openRuns,
    completedRuns = healthStats.completedRuns,
    completedRate = healthStats.completedRate,
    abandonedAtPlanningRuns = healthStats.abandonedAtPlanningRuns,
    abandonedAtImplementationRuns = healthStats.abandonedAtImplementationRuns,
    abandonedAtReviewRuns = healthStats.abandonedAtReviewRuns,
    errorRuns = healthStats.errorRuns,
    errorRate = healthStats.errorRate,
    normalDurationRuns = healthStats.normalDurationRuns,
    syntheticZeroDurationRuns = healthStats.syntheticZeroDurationRuns,
    longRunningDurationRuns = healthStats.longRunningDurationRuns,
    invalidDurationRuns = healthStats.invalidDurationRuns,
    medianDurationSeconds = healthStats.medianDurationSeconds,
    p90DurationSeconds = healthStats.p90DurationSeconds,
    childStepCoverage = healthStats.childStepCoverage,
    featureSizeOutcomeStats = healthStats.featureSizeOutcomeStats,
    largeFeatureHealth = healthStats.largeFeatureHealth,
    featureSizeCounts = countValues(rows, "feature_size", featureSizes),
    completionStatusCounts = countValues(finishedRows, "completion_status", completionStatuses),
    auditResultCounts = countValues(finishedRows, "audit_result", auditResults),
    validationResultCounts = countValues(finishedRows, "validation_result", validationResults),
    featureFlagPatternCounts = countValues(finishedRows, "feature_flag_pattern", featureFlagPatterns),
    boundaryHistoryValueCounts = countValues(finishedRows, "boundary_history_value", historySignalValues),
    rolloutNeededRuns = rows.count { it.booleanValue("rollout_needed") },
    rolloutNeededRate = rate(rows.count { it.booleanValue("rollout_needed") }, rows.size),
    featureFlagUsedRuns = finishedRows.count { it.booleanValue("feature_flag_used") },
    featureFlagUsedRate = rate(finishedRows.count { it.booleanValue("feature_flag_used") }, finishedRows.size),
    prCreatedRuns = finishedRows.count { it.booleanValue("pr_created") },
    prCreatedRate = rate(finishedRows.count { it.booleanValue("pr_created") }, finishedRows.size),
    boundaryHistoryWrittenRuns = finishedRows.count { it.booleanValue("boundary_history_written") },
    boundaryHistoryWrittenRate =
    rate(finishedRows.count { it.booleanValue("boundary_history_written") }, finishedRows.size),
    averageAcceptanceCriteriaCount = averages.acceptanceCriteriaCount,
    averageSpecWordCount = averages.specWordCount,
    averageReviewIterations = averages.reviewIterations,
    averageAuditIterations = averages.auditIterations,
    averageFilesCreated = averages.filesCreated,
    averageFilesModified = averages.filesModified,
    averageTasksCompleted = averages.tasksCompleted,
    averageDurationSeconds = averages.durationSeconds,
  )
}

fun loadRows(connection: Connection, tableName: String): List<Map<String, Any?>> =
  connection.prepareStatement("SELECT * FROM $tableName ORDER BY started_at, session_id").use { statement ->
    statement.executeQuery().use(::collectRows)
  }

fun finishedRows(rows: List<Map<String, Any?>>): List<Map<String, Any?>> =
  rows.filter { it["finished_at"]?.toString()?.isNotBlank() == true }

fun historySignalsPresent(row: Map<String, Any?>): Boolean =
  row.stringValue("history_relevance") != "none" || row.stringValue("history_helpfulness") != "none"

fun countValues(rows: List<Map<String, Any?>>, columnName: String, expectedValues: List<String>): Map<String, Int> {
  val counts = expectedValues.associateWith { 0 }.toMutableMap()
  rows.forEach { row ->
    val rawValue = row.stringValue(columnName)
    if (rawValue in counts) {
      counts[rawValue] = counts.getValue(rawValue) + 1
    }
  }
  return counts
}
