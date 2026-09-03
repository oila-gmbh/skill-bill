package skillbill.cli.goal

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
