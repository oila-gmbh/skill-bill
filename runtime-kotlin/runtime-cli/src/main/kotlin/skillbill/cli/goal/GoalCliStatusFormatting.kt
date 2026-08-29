package skillbill.cli.goal

import skillbill.application.goalrunner.model.GoalRunnerStatusRequest
import skillbill.application.goalrunner.model.GoalRunnerStopStatus
import skillbill.cli.core.CliRunState
import skillbill.cli.core.detectInvokingAgentId
import skillbill.error.DatabaseAccessError
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import java.nio.file.Path

internal data class GoalStatusCliRequestOptions(
  val issueKey: String,
  val monitorOnly: Boolean = false,
  val agent: String?,
  val agentOverride: String?,
  val repoRoot: String?,
  val diff: GoalStatusCliDiffOptions = GoalStatusCliDiffOptions(),
)

internal data class GoalStatusCliDiffOptions(
  val includeDiffStat: Boolean = false,
  val selectedDiffHunkPaths: List<String> = emptyList(),
  val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
)

internal fun CliRunState.goalStatusRequest(options: GoalStatusCliRequestOptions): GoalRunnerStatusRequest =
  GoalRunnerStatusRequest(
    issueKey = options.issueKey,
    invokedAgentId = detectInvokingAgentId(options.agent, environment),
    configuredAgentOverrideId = options.agentOverride,
    dbPathOverride = dbOverride,
    repoRoot = options.repoRoot?.let(Path::of)
      ?.let { root -> if (options.monitorOnly) canonicalRepositoryRoot(root) else root.toAbsolutePath().normalize() }
      ?: if (options.monitorOnly) canonicalRepositoryRoot(Path.of("")) else Path.of("").toAbsolutePath().normalize(),
    includeDiffStat = options.diff.includeDiffStat,
    selectedDiffHunkPaths = options.diff.selectedDiffHunkPaths,
    selectedDiffMaxHunks = options.diff.selectedDiffMaxHunks,
    selectedDiffMaxLines = options.diff.selectedDiffMaxLines,
    selectedDiffMaxBytes = options.diff.selectedDiffMaxBytes,
  )

internal fun GoalRunnerStatusProjection?.toGoalStatusCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf<String, Any?>(
    "status" to "ok",
    "issue_key" to it.issueKey,
    "complete_count" to it.completeCount,
    "pending_count" to it.pendingCount,
    "blocked_count" to it.blockedCount,
    "current_subtask" to it.currentSubtaskId,
    "current_step" to it.currentStep,
    "active_agent" to it.activeAgent,
    "execution_liveness" to it.executionLiveness.wireValue,
    "latest_liveness_signal" to it.latestLivenessSignal,
    "paused" to it.paused,
    "pause_requested" to it.pauseRequested,
    "pause_reason" to it.pauseReason,
    "stop_after_subtask" to it.stopAfterSubtaskId,
  ).apply {
    it.planning?.let { planning ->
      put(
        "planning",
        linkedMapOf(
          "state" to planning.state.wireValue,
          "shared_preplan_prepared" to planning.sharedPreplanPrepared,
          "planned_subtask_count" to planning.plannedSubtaskCount,
          "total_subtask_count" to planning.totalSubtaskCount,
          "current_planning_subtask" to planning.currentPlanningSubtaskId,
          "reason" to planning.reason,
        ),
      )
    }
    it.latestObservabilityEvent?.let { event -> put("latest_observability_event", event) }
    it.requestedDiffStat?.let { stat -> put("diff_stat", stat.toGoalDiffStatCliMap()) }
    it.selectedDiffHunks?.let { hunks -> put("selected_diff_hunks", hunks.toGoalSelectedDiffHunksCliMap()) }
    putGoalLedgerCliEntries(it)
    it.outOfBandAcceptances.toGoalAcceptanceCliList()?.let { list -> put("out_of_band_acceptances", list) }
  }
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "complete_count" to 0,
  "pending_count" to 0,
  "blocked_count" to 0,
  "current_subtask" to null,
  "current_step" to null,
  "active_agent" to null,
  "execution_liveness" to ExecutionLiveness.UNKNOWN.wireValue,
  "latest_liveness_signal" to null,
  "paused" to false,
  "pause_requested" to false,
  "pause_reason" to null,
  "stop_after_subtask" to null,
)

internal fun GoalRunnerStatusProjection?.toBoundedGoalStatusCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf(
    "complete_count" to it.completeCount,
    "pending_count" to it.pendingCount,
    "blocked_count" to it.blockedCount,
    "current_subtask" to it.currentSubtaskId,
    "current_step" to it.currentStep?.let(::singleLineBounded),
    "execution_liveness" to it.executionLiveness.wireValue,
    "resumable_state" to it.monitorResumableState(),
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to singleLineBounded(issueKey),
  "resumable_state" to "not_found",
)

internal fun databaseUnavailableGoalStatusCliMap(issueKey: String, error: DatabaseAccessError): Map<String, Any?> =
  linkedMapOf(
    "status" to GOAL_STATUS_DATABASE_UNAVAILABLE,
    "issue_key" to singleLineBounded(issueKey),
    "resumable_state" to GOAL_STATUS_DATABASE_UNAVAILABLE,
    "reason" to singleLineBounded(error.condition),
  )

internal fun GoalRunnerStatusProjection.monitorResumableState(): String = currentStep.let { step ->
  when {
    paused -> "paused"
    pauseRequested -> "pause_requested"
    currentSubtaskId == null && pendingCount == 0 && blockedCount == 0 -> "complete"
    step.isNullOrBlank() -> "resumable"
    else -> "resumable_at:${singleLineBounded(step)}"
  }
}

internal fun MutableMap<String, Any?>.putGoalLedgerCliEntries(projection: GoalRunnerStatusProjection) {
  if (projection.blockedAttemptCount > 0) put("blocked_attempt_count", projection.blockedAttemptCount)
  if (projection.supervisorKillCount > 0) put("supervisor_kill_count", projection.supervisorKillCount)
  if (projection.phaseAttemptCounts.isNotEmpty()) put("phase_attempt_counts", projection.phaseAttemptCounts)
  if (projection.cumulativeFixIterations.isNotEmpty()) {
    put("cumulative_fix_iterations", projection.cumulativeFixIterations)
  }
  if (projection.reAttemptCauseCounts.isNotEmpty()) put("re_attempt_causes", projection.reAttemptCauseCounts)
  projection.findingsInScope?.let { count -> put("findings_in_scope", count) }
}

internal fun List<GoalRunnerAcceptedSubtask>.toGoalAcceptanceCliList(): List<Map<String, Any?>>? = takeIf {
  it.isNotEmpty()
}?.map { acceptance ->
  linkedMapOf(
    "subtask_id" to acceptance.subtaskId,
    "commit_sha" to acceptance.commitSha,
    "reason" to acceptance.reason,
    "accepted_at" to acceptance.acceptedAt,
  )
}

internal fun goalStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_subtask: ${payload["current_subtask"] ?: "none"}")
  appendLine("current_step: ${payload["current_step"] ?: "none"}")
  appendLine("active_agent: ${payload["active_agent"] ?: "none"}")
  appendLine("execution_liveness: ${payload["execution_liveness"]}")
  appendLine("latest_liveness_signal: ${payload["latest_liveness_signal"] ?: "none"}")
  appendLine("paused: ${payload["paused"]}")
  appendLine("pause_requested: ${payload["pause_requested"]}")
  appendLine("pause_reason: ${payload["pause_reason"] ?: "none"}")
  appendLine("stop_after_subtask: ${payload["stop_after_subtask"] ?: "none"}")
  (payload["planning"] as? Map<*, *>)?.let { planning ->
    appendLine(
      "planning: state=${planning["state"]} shared_preplan=${planning["shared_preplan_prepared"]} " +
        "planned=${planning["planned_subtask_count"]}/${planning["total_subtask_count"]} " +
        "current=${planning["current_planning_subtask"] ?: "none"}",
    )
    planning["reason"]?.let { appendLine("planning_reason: $it") }
  }
  (payload["latest_observability_event"] as? Map<*, *>)?.let { event ->
    appendLine(
      "latest_observability: phase=${event["workflow_phase"]} role=${event["worker_role"]} " +
        "liveness=${event["liveness_class"]} sequence=${event["sequence_number"]}",
    )
  }
  appendOperatorSurfaceLines(payload)
  appendDiffStatusLines(payload)
}

internal fun goalMonitorStatusText(payload: Map<String, Any?>): String = if (payload["status"] == "not_found") {
  buildString {
    appendLine("goal: ${payload["issue_key"]}")
    appendLine("status: not_found")
    appendLine("resumable_state: not_found")
  }
} else if (payload["status"] == GOAL_STATUS_DATABASE_UNAVAILABLE) {
  buildString {
    appendLine("goal: ${payload["issue_key"]}")
    appendLine("status: $GOAL_STATUS_DATABASE_UNAVAILABLE")
    appendLine("resumable_state: $GOAL_STATUS_DATABASE_UNAVAILABLE")
    appendLine("reason: ${payload["reason"]}")
  }
} else {
  buildString {
    appendLine("complete: ${payload["complete_count"]}")
    appendLine("pending: ${payload["pending_count"]}")
    appendLine("blocked: ${payload["blocked_count"]}")
    appendLine("current_subtask: ${payload["current_subtask"] ?: "none"}")
    appendLine("current_step: ${payload["current_step"] ?: "none"}")
    appendLine("execution_liveness: ${payload["execution_liveness"]}")
    appendLine("resumable_state: ${payload["resumable_state"]}")
  }
}

internal fun Map<String, Any?>.goalStatusExitCode(): Int = if (!containsKey("status") || this["status"] == "ok") 0 else 1

internal fun Map<String, Any?>.goalPauseExitCode(): Int = if (this["status"] != "not_found") 0 else 1

// Idempotent outcomes exit 0; a refused stop is a non-zero failure the operator must act on.
internal fun Map<String, Any?>.goalStopExitCode(): Int = when (this["status"]) {
  GoalRunnerStopStatus.STOPPED.wireValue,
  GoalRunnerStopStatus.ALREADY_STOPPED.wireValue,
  GoalRunnerStopStatus.NO_LIVE_LEASE.wireValue,
  -> 0
  else -> 1
}

internal fun Map<String, Any?>.withWatchRefresh(refreshIndex: Int): Map<String, Any?> =
  linkedMapOf<String, Any?>("refresh_index" to refreshIndex).apply { putAll(this@withWatchRefresh) }

internal fun canonicalRepositoryRoot(start: Path): Path {
  val resolvedStart = start.toAbsolutePath().normalize().toRealPath()
  var candidate = resolvedStart
  while (!candidate.resolve(".git").toFile().exists()) {
    candidate = candidate.parent ?: return resolvedStart
  }
  return candidate.toRealPath()
}

internal fun Map<String, Any?>.goalWatchStopReason(refreshCount: Int, maxRefreshes: Int, idleStop: Boolean): String? =
  when {
    this["status"] == "not_found" -> "not_found"
    // Only a reached pause is terminal. `pause_requested` is deferred to the next launch boundary, so
    // the current subtask keeps running; stopping on the request blinds the monitor for the rest of it.
    this["paused"] == true -> "goal_paused"
    (this["pending_count"] as? Number)?.toInt() == 0 -> "goal_terminal"
    idleStop -> "goal_idle"
    maxRefreshes > 0 && refreshCount >= maxRefreshes -> "max_refreshes"
    else -> null
  }

internal fun goalWatchText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("refresh_count: ${payload["refresh_count"]}")
  appendLine("interval_seconds: ${payload["interval_seconds"]}")
  appendLine("stop_reason: ${payload["stop_reason"]}")
  val latestRefresh = payload["latest_refresh"] as? Map<*, *> ?: return@buildString
  append(goalWatchRefreshText(latestRefresh))
}

internal fun goalWatchRefreshText(refresh: Map<*, *>): String = buildString {
  appendLine(
    "watch_refresh: index=${refresh["refresh_index"]} status=${refresh["status"]} " +
      "current_subtask=${refresh["current_subtask"] ?: "none"} " +
      "current_step=${refresh["current_step"] ?: "none"} " +
      "execution_liveness=${refresh["execution_liveness"] ?: "unknown"} " +
      "liveness=${refresh["latest_liveness_signal"] ?: "none"}",
  )
  (refresh["latest_observability_event"] as? Map<*, *>)?.let { event ->
    appendLine(
      "watch_observability: index=${refresh["refresh_index"]} phase=${event["workflow_phase"]} " +
        "role=${event["worker_role"]} liveness=${event["liveness_class"]} " +
        "sequence=${event["sequence_number"]}",
    )
  }
  appendDiffStatusLines(refresh, watchIndex = refresh["refresh_index"]?.toString())
}

internal fun StringBuilder.appendOperatorSurfaceLines(payload: Map<*, *>) {
  val blockedAttemptCount = (payload["blocked_attempt_count"] as? Number)?.toInt() ?: 0
  val supervisorKillCount = (payload["supervisor_kill_count"] as? Number)?.toInt() ?: 0
  if (blockedAttemptCount > 0 || supervisorKillCount > 0) {
    appendLine("blocked_attempts: $blockedAttemptCount supervisor_kills: $supervisorKillCount")
  }
  (payload["phase_attempt_counts"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { counts ->
    appendLine("phase_attempts: ${counts.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["cumulative_fix_iterations"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { iters ->
    appendLine("fix_iterations: ${iters.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["re_attempt_causes"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { causes ->
    appendLine("re_attempt_causes: ${causes.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["findings_in_scope"] as? Number)?.toInt()?.let { appendLine("findings_in_scope: $it") }
  (payload["out_of_band_acceptances"] as? List<*>)?.takeIf(List<*>::isNotEmpty)?.forEach { raw ->
    val acceptance = raw as? Map<*, *> ?: return@forEach
    appendLine(
      "accepted_out_of_band: subtask=${acceptance["subtask_id"]} commit=${acceptance["commit_sha"]} " +
        "at=${acceptance["accepted_at"]} reason=${acceptance["reason"]}",
    )
  }
}

internal fun StringBuilder.appendDiffStatusLines(payload: Map<*, *>, watchIndex: String? = null) {
  val indexPrefix = watchIndex?.let { " index=$it" }.orEmpty()
  (payload["diff_stat"] as? Map<*, *>)?.let { stat ->
    appendLine(
      "${if (watchIndex == null) "diff_stat" else "watch_diff_stat"}:$indexPrefix " +
        "files_changed=${stat["files_changed"]} insertions=${stat["insertions"]} deletions=${stat["deletions"]}",
    )
  }
  val selected = payload["selected_diff_hunks"] as? Map<*, *> ?: return
  val hunks = (selected["hunks"] as? List<*>).orEmpty()
  appendLine(
    "${if (watchIndex == null) "selected_diff_hunks" else "watch_selected_diff_hunks"}:$indexPrefix " +
      "count=${hunks.size} truncated=${selected["truncated"]}",
  )
  hunks.forEachIndexed { hunkIndex, rawHunk ->
    val hunk = rawHunk as? Map<*, *> ?: return@forEachIndexed
    val path = hunk["path"].toString().goalCliToken()
    val staged = hunk["staged"]
    val lines = (hunk["lines"] as? List<*>).orEmpty()
    appendLine(
      "${if (watchIndex == null) "selected_diff_hunk" else "watch_selected_diff_hunk"}:$indexPrefix " +
        "hunk_index=${hunkIndex + 1} path=$path staged=$staged " +
        "header=${hunk["header"].toString().goalCliToken()} line_count=${lines.size} truncated=${hunk["truncated"]}",
    )
    lines.forEachIndexed { lineIndex, rawLine ->
      appendLine(
        "${if (watchIndex == null) "selected_diff_line" else "watch_selected_diff_line"}:$indexPrefix " +
          "hunk_index=${hunkIndex + 1} line_index=${lineIndex + 1} path=$path staged=$staged " +
          "text=${rawLine.toString().goalCliToken()}",
      )
    }
  }
}

internal fun String.goalCliToken(): String = replace("\\", "\\\\")
  .replace("\t", "\\t")
  .replace(" ", "\\s")

