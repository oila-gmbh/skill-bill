package skillbill.cli.goal

import skillbill.application.goalrunner.model.GoalRunnerAcceptResult
import skillbill.application.goalrunner.model.GoalRunnerReplanResult
import skillbill.application.goalrunner.model.GoalRunnerReplanSnapshot
import skillbill.application.goalrunner.model.GoalRunnerResetResult
import skillbill.application.goalrunner.model.GoalRunnerResetSnapshot
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask

internal fun GoalRunnerResetResult?.toGoalResetCliMap(issueKey: String, hard: Boolean): Map<String, Any?> = this?.let {
  linkedMapOf(
    "status" to if (it.recovery?.recoveryCommand == null) "ok" else "recovery_required",
    "issue_key" to it.issueKey,
    "mode" to it.mode,
    "parent_workflow_id" to it.parentWorkflowId,
    "before" to resetSnapshotMap(it.before),
    "after" to resetSnapshotMap(it.after),
    "recovery" to it.recovery?.let { recovery ->
      linkedMapOf(
        "subtask_id" to recovery.subtaskId,
        "workflow_id" to recovery.workflowId,
        "classification" to recovery.classification,
        "command" to recovery.recoveryCommand,
      )
    },
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "mode" to if (hard) "hard" else "soft",
)

internal fun GoalRunnerReplanResult?.toGoalReplanCliMap(issueKey: String): Map<String, Any?> = this?.let {
  linkedMapOf(
    "status" to "ok",
    "issue_key" to it.issueKey,
    "mode" to "scoped_replan",
    "parent_workflow_id" to it.parentWorkflowId,
    "subtask_id" to it.subtaskId,
    "discarded_plan" to it.discardedPlan,
    "discarded_shared_preplan" to it.discardedSharedPreplan,
    "cascaded_plan_subtask_ids" to it.cascadedPlanSubtaskIds,
    "cleared_child_subtask_ids" to it.clearedChildSubtaskIds,
    "before" to replanSnapshotMap(it.before),
    "after" to replanSnapshotMap(it.after),
  )
} ?: linkedMapOf(
  "status" to "not_found",
  "issue_key" to issueKey,
  "mode" to "scoped_replan",
)

internal fun resetSnapshotMap(snapshot: GoalRunnerResetSnapshot): Map<String, Any?> =
  linkedMapOf(
    "status" to snapshot.status,
    "current_subtask" to snapshot.currentSubtaskId,
    "current_action" to snapshot.currentAction,
    "subtasks" to snapshot.subtasks.map { subtask ->
      linkedMapOf(
        "id" to subtask.id,
        "status" to subtask.status,
        "branch" to subtask.branch,
        "workflow_id" to subtask.workflowId,
        "commit_sha" to subtask.commitSha,
        "blocked_reason" to subtask.blockedReason,
        "last_resumable_step" to subtask.lastResumableStep,
      )
    },
  )

internal fun replanSnapshotMap(snapshot: GoalRunnerReplanSnapshot): Map<String, Any?> =
  linkedMapOf(
    "status" to snapshot.status,
    "current_subtask" to snapshot.currentSubtaskId,
    "current_action" to snapshot.currentAction,
    "shared_preplan_prepared" to snapshot.sharedPreplanPrepared,
    "planned_subtask_ids" to snapshot.plannedSubtaskIds,
    "subtasks" to snapshot.subtasks.map { subtask ->
      linkedMapOf(
        "id" to subtask.id,
        "status" to subtask.status,
        "branch" to subtask.branch,
        "workflow_id" to subtask.workflowId,
        "commit_sha" to subtask.commitSha,
        "blocked_reason" to subtask.blockedReason,
        "last_resumable_step" to subtask.lastResumableStep,
      )
    },
  )

internal fun GoalRunnerAcceptResult.toGoalAcceptCliMap(): Map<String, Any?> = when (this) {
  is GoalRunnerAcceptResult.Accepted -> linkedMapOf(
    "status" to "ok",
    "issue_key" to issueKey,
    "parent_workflow_id" to parentWorkflowId,
    "subtask_id" to subtaskId,
    "commit_sha" to commitSha,
    "reason" to reason,
    "accepted_at" to acceptedAt,
    "after" to resetSnapshotMap(after),
  )
  is GoalRunnerAcceptResult.Rejected -> linkedMapOf(
    "status" to "rejected",
    "issue_key" to issueKey,
    "reason" to reason,
  )
}

internal fun goalAcceptText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("reason: ${payload["reason"]}")
  payload["subtask_id"]?.let { appendLine("accepted_subtask: $it") }
  payload["commit_sha"]?.let { appendLine("commit_sha: $it") }
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  (payload["after"] as? Map<*, *>)?.let { after ->
    appendLine("after: status=${after["status"]}; current_subtask=${after["current_subtask"] ?: "none"}")
    appendLine("after_subtasks:")
    appendGoalResetSubtaskLines(this, after["subtasks"] as? List<*>)
  }
}

internal fun hardResetAcceptanceWarning(issueKey: String, records: List<GoalRunnerAcceptedSubtask>): String =
  buildString {
    appendLine("hard_reset_acceptances_to_discard:")
    records.forEach { record ->
      val command = listOf(
        "skill-bill",
        "goal",
        "accept",
        issueKey,
        "--subtask",
        record.subtaskId.toString(),
        "--commit",
        record.commitSha,
        "--reason",
        record.reason,
        "--restore-after-hard-reset",
      ).joinToString(" ", transform = String::shellWord)
      appendLine(
        "acceptance: subtask=${record.subtaskId}; commit=${record.commitSha}; reason=${record.reason}",
      )
      appendLine("restore_command: $command")
    }
  }

internal fun String.shellWord(): String = if (isNotEmpty() && all { it.isLetterOrDigit() || it in "-._/:@" }) {
  this
} else {
  "'${replace("'", "'\"'\"'")}'"
}

internal fun goalResetText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("mode: ${payload["mode"]}")
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  val before = payload["before"] as? Map<*, *>
  val after = payload["after"] as? Map<*, *>
  if (before != null && after != null) {
    appendLine("before: status=${before["status"]}; current_subtask=${before["current_subtask"] ?: "none"}")
    appendLine("after: status=${after["status"]}; current_subtask=${after["current_subtask"] ?: "none"}")
    appendLine("before_subtasks:")
    appendGoalResetSubtaskLines(this, before["subtasks"] as? List<*>)
    appendLine("after_subtasks:")
    appendGoalResetSubtaskLines(this, after["subtasks"] as? List<*>)
  }
  (payload["recovery"] as? Map<*, *>)?.let { recovery ->
    appendLine(
      "recovery: subtask=${recovery["subtask_id"]}; workflow_id=${recovery["workflow_id"]}; " +
        "classification=${recovery["classification"]}",
    )
    recovery["command"]?.let { appendLine("recovery_command: $it") }
  }
}

internal fun goalReplanText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("mode: ${payload["mode"]}")
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  payload["subtask_id"]?.let { appendLine("discarded_plan: subtask=$it; existed=${payload["discarded_plan"]}") }
  val discardedShared = payload["discarded_shared_preplan"] as? Boolean == true
  val cascaded = (payload["cascaded_plan_subtask_ids"] as? List<*>).orEmpty().filterNotNull()
  if (discardedShared || cascaded.isNotEmpty()) {
    appendLine(
      "discarded_shared_preplan: $discardedShared; " +
        "cascaded_plans=${cascaded.joinToString(",").ifEmpty { "none" }}",
    )
  }
  val before = payload["before"] as? Map<*, *>
  val after = payload["after"] as? Map<*, *>
  if (before != null && after != null) {
    appendLine(
      "preserved: shared_preplan=${after["shared_preplan_prepared"]}; " +
        "planned_before=${(before["planned_subtask_ids"] as? List<*>)?.joinToString(",") ?: "none"}; " +
        "planned_after=${(after["planned_subtask_ids"] as? List<*>)?.joinToString(",") ?: "none"}",
    )
    appendLine("before: status=${before["status"]}; current_subtask=${before["current_subtask"] ?: "none"}")
    appendLine("after: status=${after["status"]}; current_subtask=${after["current_subtask"] ?: "none"}")
    appendLine("before_subtasks:")
    appendGoalResetSubtaskLines(this, before["subtasks"] as? List<*>)
    appendLine("after_subtasks:")
    appendGoalResetSubtaskLines(this, after["subtasks"] as? List<*>)
  }
}

// --agent-override is independent and continues to win at the
// AgentRunService.effectiveAgent seam; this only sources invokedAgentId.
