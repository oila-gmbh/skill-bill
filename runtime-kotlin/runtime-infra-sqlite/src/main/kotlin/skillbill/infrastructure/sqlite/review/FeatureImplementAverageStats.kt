package skillbill.infrastructure.sqlite.review

import skillbill.db.telemetry.durationSeconds

data class FeatureImplementAverageStats(
  val acceptanceCriteriaCount: Double,
  val specWordCount: Double,
  val reviewIterations: Double,
  val auditIterations: Double,
  val filesCreated: Double,
  val filesModified: Double,
  val tasksCompleted: Double,
  val durationSeconds: Double,
)

fun buildFeatureImplementAverageStats(
  rows: List<Map<String, Any?>>,
  finishedRows: List<Map<String, Any?>>,
  normalDurations: List<Int>,
): FeatureImplementAverageStats = FeatureImplementAverageStats(
  acceptanceCriteriaCount = average(rows.mapNotNull { it.intValue("acceptance_criteria_count") }),
  specWordCount = average(rows.mapNotNull { it.intValue("spec_word_count") }),
  reviewIterations = average(finishedRows.mapNotNull { it.intValue("review_iterations") }),
  auditIterations = average(finishedRows.mapNotNull { it.intValue("audit_iterations") }),
  filesCreated = average(finishedRows.mapNotNull { it.intValue("files_created") }),
  filesModified = average(finishedRows.mapNotNull { it.intValue("files_modified") }),
  tasksCompleted = average(finishedRows.mapNotNull { it.intValue("tasks_completed") }),
  durationSeconds = average(normalDurations),
)
