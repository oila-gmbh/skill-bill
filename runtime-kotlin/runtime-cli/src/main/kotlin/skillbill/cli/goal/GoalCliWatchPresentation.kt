package skillbill.cli.goal

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
