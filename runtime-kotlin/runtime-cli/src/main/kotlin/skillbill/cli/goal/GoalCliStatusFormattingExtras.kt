package skillbill.cli.goal

import skillbill.application.goalrunner.model.GoalRunnerStopStatus

internal fun Map<String, Any?>.goalStatusExitCode(): Int = if (!containsKey(
    "status",
  ) || this["status"] == "ok"
) {
  0
} else {
  1
}

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
