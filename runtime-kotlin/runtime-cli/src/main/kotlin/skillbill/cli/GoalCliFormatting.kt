package skillbill.cli

internal fun appendGoalResetSubtaskLines(builder: StringBuilder, subtasks: List<*>?) {
  subtasks.orEmpty().forEach { raw ->
    val subtask = raw as? Map<*, *> ?: return@forEach
    builder.append("  - ")
    builder.append("id=")
    builder.append(subtask["id"])
    builder.append("; status=")
    builder.append(subtask["status"])
    builder.append("; workflow_id=")
    builder.append(subtask["workflow_id"] ?: "none")
    builder.append("; commit_sha=")
    builder.append(subtask["commit_sha"] ?: "none")
    builder.append("; blocked_reason=")
    builder.append(subtask["blocked_reason"] ?: "none")
    builder.append("; last_resumable_step=")
    builder.append(subtask["last_resumable_step"] ?: "none")
    builder.append('\n')
  }
}

internal fun Map<String, Any?>.goalResetExitCode(): Int = if (this["status"] == "ok") 0 else 1
