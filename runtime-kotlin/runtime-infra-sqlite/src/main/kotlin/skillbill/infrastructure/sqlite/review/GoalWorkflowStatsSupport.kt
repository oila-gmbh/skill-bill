package skillbill.infrastructure.sqlite.review

import skillbill.review.model.GoalBlockedSubtaskSummary
import skillbill.review.model.GoalModeStats
import skillbill.review.model.GoalRunSummary
import skillbill.review.model.GoalWorkflowStats
import java.sql.Connection
import java.util.Locale

// SKILL-66 Subtask 2: goal-run aggregate read. Deliberately isolated from the
// permissive `ReviewStatsUtilitySupport` accessors (`stringValue`/`intValue`/
// `booleanValue`), which silently default malformed values. Goal rows are
// parsed through the strict accessors in `GoalTelemetryRowAccessors` that throw
// `InvalidGoalTelemetryRowError` on any malformed value, so the stats read
// loud-fails instead of producing best-effort numbers (AC#5).

private val goalFinishedStatuses = listOf("completed", "blocked")
private val goalSubtaskStatuses = listOf("complete", "blocked", "skipped")

fun loadGoalRows(connection: Connection, tableName: String): List<Map<String, Any?>> =
  connection.prepareStatement("SELECT * FROM $tableName").use { statement ->
    statement.executeQuery().use(::collectRows)
  }

fun buildGoalStats(runRows: List<Map<String, Any?>>, subtaskRows: List<Map<String, Any?>>): GoalWorkflowStats {
  val runs = runRows.map(::parseGoalRunRow)
  val subtasks = subtaskRows.map(::parseGoalSubtaskRow)
  val finished = runs.filter { it.finishedAt.isNotBlank() }
  val completedRuns = finished.count { it.status == "completed" }
  val blockedRuns = finished.count { it.status == "blocked" }
  val mostRecent = runs.maxByOrNull { it.startedAt }
  val byMode = runs.groupBy { it.mode }.mapValues { (_, modeRuns) ->
    val modeFinished = modeRuns.filter { it.finishedAt.isNotBlank() }
    val modeCompleted = modeFinished.count { it.status == "completed" }
    val modeBlocked = modeFinished.count { it.status == "blocked" }
    GoalModeStats(
      totalRuns = modeRuns.size,
      finishedRuns = modeFinished.size,
      inProgressRuns = modeRuns.size - modeFinished.size,
      completedRuns = modeCompleted,
      completedRate = rate(modeCompleted, modeFinished.size),
      blockedRuns = modeBlocked,
      blockedRate = rate(modeBlocked, modeFinished.size),
      averageRunDurationMs = averageMillis(modeFinished.map { it.durationMs }),
    )
  }
  return GoalWorkflowStats(
    totalRuns = runs.size,
    finishedRuns = finished.size,
    inProgressRuns = runs.size - finished.size,
    completionStatusCounts = goalFinishedStatuses.associateWith { status -> finished.count { it.status == status } },
    completedRuns = completedRuns,
    completedRate = rate(completedRuns, finished.size),
    blockedRuns = blockedRuns,
    blockedRate = rate(blockedRuns, finished.size),
    subtaskOutcomeCounts =
    goalSubtaskStatuses.associateWith { status -> subtasks.count { it.status == status } },
    totalSubtaskEvents = subtasks.size,
    averageRunDurationMs = averageMillis(finished.map { it.durationMs }),
    averageSubtaskDurationMs = averageMillis(subtasks.map { it.durationMs }),
    averageAttemptCount = average(subtasks.map { it.attemptCount }),
    mostRecentRun = mostRecent?.let { run ->
      GoalRunSummary(
        workflowId = run.workflowId,
        issueKey = run.issueKey,
        featureName = run.featureName,
        status = run.status,
        startedAt = run.startedAt,
        finishedAt = run.finishedAt,
        durationMs = run.durationMs,
        resumed = run.resumed,
        subtaskTotal = run.subtaskTotal,
      )
    },
    topBlockedSubtasks = subtasks
      .filter { it.status == "blocked" }
      .map { s ->
        GoalBlockedSubtaskSummary(
          subtaskId = s.subtaskId,
          subtaskName = s.subtaskName,
          issueKey = s.issueKey,
          blockedReason = s.blockedReason ?: "",
          attemptCount = s.attemptCount,
        )
      },
    byMode = byMode,
  )
}

internal data class GoalRunRow(
  val workflowId: String,
  val issueKey: String,
  val featureName: String,
  val subtaskTotal: Int,
  val resumed: Boolean,
  val startedAt: String,
  val status: String,
  val finishedAt: String,
  val durationMs: Long,
  val mode: String,
)

internal data class GoalSubtaskRow(
  val subtaskId: Int,
  val subtaskName: String,
  val issueKey: String,
  val blockedReason: String?,
  val status: String,
  val durationMs: Long,
  val attemptCount: Int,
)

private fun parseGoalRunRow(row: Map<String, Any?>): GoalRunRow {
  val identity = "goal_run_sessions[workflow_id=${row["workflow_id"] ?: "<null>"}]"
  val finishedAtRaw = row["finished_at"]?.toString().orEmpty()
  val finished = finishedAtRaw.isNotBlank()
  if (finished) {
    row.requireNonNegativeInt("subtasks_complete", identity)
    row.requireNonNegativeInt("subtasks_blocked", identity)
    row.requireNonNegativeInt("subtasks_skipped", identity)
  }
  return GoalRunRow(
    workflowId = row.requireNonBlankString("workflow_id", identity),
    issueKey = row.requireNonBlankString("issue_key", identity),
    featureName = row.requirePresentString("feature_name", identity),
    subtaskTotal = row.requireNonNegativeInt("subtask_total", identity),
    resumed = row.requireBooleanInt("resumed", identity),
    startedAt = row.requireNonBlankString("started_at", identity),
    status = if (finished) row.requireEnum("status", goalFinishedStatuses, identity) else "",
    finishedAt = finishedAtRaw,
    durationMs = if (finished) row.requireNonNegativeLong("finished_duration_ms", identity) else 0L,
    mode = row.requireNonBlankString("mode", identity),
  )
}

private fun parseGoalSubtaskRow(row: Map<String, Any?>): GoalSubtaskRow {
  val identity =
    "goal_subtask_events[issue_key=${row["issue_key"] ?: "<null>"}, " +
      "subtask_id=${row["subtask_id"] ?: "<null>"}, workflow_id=${row["workflow_id"] ?: "<null>"}]"
  row.requireNonBlankString("started_at", identity)
  row.requireNonBlankString("finished_at", identity)
  return GoalSubtaskRow(
    subtaskId = row.requirePositiveInt("subtask_id", identity),
    subtaskName = row.requirePresentString("subtask_name", identity),
    issueKey = row.requireNonBlankString("issue_key", identity),
    blockedReason = row["blocked_reason"]?.toString(),
    status = row.requireEnum("status", goalSubtaskStatuses, identity),
    durationMs = row.requireNonNegativeLong("duration_ms", identity),
    attemptCount = row.requireNonNegativeInt("attempt_count", identity),
  )
}

private fun averageMillis(values: List<Long>): Double = if (values.isEmpty()) {
  0.0
} else {
  String.format(Locale.US, "%.2f", values.sum().toDouble() / values.size).toDouble()
}
