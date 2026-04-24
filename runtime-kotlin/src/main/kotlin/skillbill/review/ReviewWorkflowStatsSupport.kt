package skillbill.review

import java.sql.Connection

private val featureVerifyCompletionStatuses =
  listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error")
private val auditResults = listOf("all_pass", "had_gaps", "skipped")
private val historySignalValues = listOf("none", "irrelevant", "low", "medium", "high")
private val featureSizes = listOf("SMALL", "MEDIUM", "LARGE")
private val completionStatuses =
  listOf(
    "completed",
    "abandoned_at_planning",
    "abandoned_at_implementation",
    "abandoned_at_review",
    "error",
  )
private val validationResults = listOf("pass", "fail", "skipped")
private val featureFlagPatterns = listOf("simple_conditional", "di_switch", "legacy", "none")

fun buildFeatureVerifyStatsPayload(rows: List<Map<String, Any?>>): Map<String, Any?> {
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
  return mapOf(
    "workflow" to "bill-feature-verify",
    "total_runs" to rows.size,
    "finished_runs" to finishedRows.size,
    "in_progress_runs" to rows.size - finishedRows.size,
    "completion_status_counts" to countValues(finishedRows, "completion_status", featureVerifyCompletionStatuses),
    "audit_result_counts" to countValues(finishedRows, "audit_result", auditResults),
    "rollout_relevant_runs" to rolloutRelevantRuns,
    "rollout_relevant_rate" to rate(rolloutRelevantRuns, rows.size),
    "feature_flag_audit_performed_runs" to auditPerformedRuns,
    "feature_flag_audit_performed_rate" to rate(auditPerformedRuns, finishedRows.size),
    "history_read_runs" to historyReadRuns,
    "history_read_rate" to rate(historyReadRuns, finishedRows.size),
    "history_relevant_runs" to historyRelevantRuns,
    "history_relevant_rate" to rate(historyRelevantRuns, finishedRows.size),
    "history_helpful_runs" to historyHelpfulRuns,
    "history_helpful_rate" to rate(historyHelpfulRuns, finishedRows.size),
    "history_relevance_counts" to countValues(finishedRows, "history_relevance", historySignalValues),
    "history_helpfulness_counts" to countValues(finishedRows, "history_helpfulness", historySignalValues),
    "runs_with_gaps_found" to runsWithGapsFound,
    "average_acceptance_criteria_count" to average(acceptanceCriteriaCounts),
    "average_review_iterations" to average(reviewIterations),
    "average_duration_seconds" to average(durations),
  )
}

fun buildFeatureImplementStatsPayload(rows: List<Map<String, Any?>>): Map<String, Any?> {
  val finishedRows = finishedRows(rows)
  val rolloutNeededRuns = rows.count { it.booleanValue("rollout_needed") }
  val featureFlagUsedRuns = finishedRows.count { it.booleanValue("feature_flag_used") }
  val prCreatedRuns = finishedRows.count { it.booleanValue("pr_created") }
  val boundaryHistoryWrittenRuns = finishedRows.count { it.booleanValue("boundary_history_written") }
  val acceptanceCriteriaCounts = rows.mapNotNull { it.intValue("acceptance_criteria_count") }
  val specWordCounts = rows.mapNotNull { it.intValue("spec_word_count") }
  val reviewIterations = finishedRows.mapNotNull { it.intValue("review_iterations") }
  val auditIterations = finishedRows.mapNotNull { it.intValue("audit_iterations") }
  val filesCreated = finishedRows.mapNotNull { it.intValue("files_created") }
  val filesModified = finishedRows.mapNotNull { it.intValue("files_modified") }
  val tasksCompleted = finishedRows.mapNotNull { it.intValue("tasks_completed") }
  val durations = finishedRows.map(::durationSeconds).filter { it > 0 }
  return mapOf(
    "workflow" to "bill-feature-implement",
    "total_runs" to rows.size,
    "finished_runs" to finishedRows.size,
    "in_progress_runs" to rows.size - finishedRows.size,
    "feature_size_counts" to countValues(rows, "feature_size", featureSizes),
    "completion_status_counts" to countValues(finishedRows, "completion_status", completionStatuses),
    "audit_result_counts" to countValues(finishedRows, "audit_result", auditResults),
    "validation_result_counts" to countValues(finishedRows, "validation_result", validationResults),
    "feature_flag_pattern_counts" to countValues(finishedRows, "feature_flag_pattern", featureFlagPatterns),
    "boundary_history_value_counts" to countValues(finishedRows, "boundary_history_value", historySignalValues),
    "rollout_needed_runs" to rolloutNeededRuns,
    "rollout_needed_rate" to rate(rolloutNeededRuns, rows.size),
    "feature_flag_used_runs" to featureFlagUsedRuns,
    "feature_flag_used_rate" to rate(featureFlagUsedRuns, finishedRows.size),
    "pr_created_runs" to prCreatedRuns,
    "pr_created_rate" to rate(prCreatedRuns, finishedRows.size),
    "boundary_history_written_runs" to boundaryHistoryWrittenRuns,
    "boundary_history_written_rate" to rate(boundaryHistoryWrittenRuns, finishedRows.size),
    "average_acceptance_criteria_count" to average(acceptanceCriteriaCounts),
    "average_spec_word_count" to average(specWordCounts),
    "average_review_iterations" to average(reviewIterations),
    "average_audit_iterations" to average(auditIterations),
    "average_files_created" to average(filesCreated),
    "average_files_modified" to average(filesModified),
    "average_tasks_completed" to average(tasksCompleted),
    "average_duration_seconds" to average(durations),
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
