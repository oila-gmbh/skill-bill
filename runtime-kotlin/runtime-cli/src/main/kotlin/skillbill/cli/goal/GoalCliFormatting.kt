package skillbill.cli.goal

import skillbill.application.model.GoalRunnerRepairResult
import skillbill.application.model.GoalRunnerRepairStatus

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

internal fun GoalRunnerRepairResult.toGoalRepairCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to status.wireValue,
  "issue_key" to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "refusal_reason" to refusalReason,
  "live_lease_workflow_id" to liveLeaseWorkflowId,
  "diagnoses" to diagnoses.map { diagnosis ->
    linkedMapOf(
      "subtask_id" to diagnosis.subtaskId,
      "workflow_id" to diagnosis.workflowId,
      "healthy" to diagnosis.isHealthy,
      "passed_checks" to diagnosis.passedChecks,
      "wedges" to diagnosis.wedges.map { wedge ->
        linkedMapOf(
          "wedge_class" to wedge.wedgeClass.wireValue,
          "field" to wedge.field,
          "current_value" to wedge.currentValue,
        )
      },
    )
  },
  "applied_repairs" to appliedRepairs.map { repair ->
    linkedMapOf(
      "subtask_id" to repair.subtaskId,
      "workflow_id" to repair.workflowId,
      "wedge_class" to repair.wedgeClass.wireValue,
      "field" to repair.field,
      "prior_value" to repair.priorValue,
      "new_value" to repair.newValue,
    )
  },
)

internal fun Map<String, Any?>.goalRepairExitCode(): Int = when (this["status"]) {
  GoalRunnerRepairStatus.HEALTHY.wireValue,
  GoalRunnerRepairStatus.REPAIRED.wireValue,
  -> 0
  GoalRunnerRepairStatus.INSPECTED.wireValue -> 2
  else -> 1
}

internal fun goalRepairText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload["issue_key"]}")
  appendLine("status: ${payload["status"]}")
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  payload["refusal_reason"]?.let { appendLine("refusal_reason: $it") }
  payload["live_lease_workflow_id"]?.let { appendLine("live_lease_workflow_id: $it") }
  appendLine("diagnoses:")
  appendGoalRepairDiagnoses(this, payload["diagnoses"] as? List<*>)
  appendGoalRepairAppliedRepairs(this, payload["applied_repairs"] as? List<*>)
}

private fun appendGoalRepairDiagnoses(builder: StringBuilder, diagnoses: List<*>?) {
  diagnoses.orEmpty().forEach { raw ->
    val diagnosis = raw as? Map<*, *> ?: return@forEach
    builder.appendLine(
      "  - subtask=${diagnosis["subtask_id"]}; workflow_id=${diagnosis["workflow_id"] ?: "none"}; " +
        "healthy=${diagnosis["healthy"]}",
    )
    (diagnosis["passed_checks"] as? List<*>).orEmpty().takeIf { it.isNotEmpty() }?.let { checks ->
      builder.appendLine("    passed_checks: ${checks.joinToString(",")}")
    }
    (diagnosis["wedges"] as? List<*>).orEmpty().forEach { wedgeRaw ->
      val wedge = wedgeRaw as? Map<*, *> ?: return@forEach
      builder.appendLine(
        "    wedge: class=${wedge["wedge_class"]}; field=${wedge["field"]}; " +
          "current_value=${wedge["current_value"] ?: "absent"}",
      )
    }
  }
}

private fun appendGoalRepairAppliedRepairs(builder: StringBuilder, repairs: List<*>?) {
  val appliedRepairs = repairs.orEmpty()
  if (appliedRepairs.isEmpty()) return
  builder.appendLine("applied_repairs:")
  appliedRepairs.forEach { raw ->
    val repair = raw as? Map<*, *> ?: return@forEach
    builder.appendLine(
      "  - subtask=${repair["subtask_id"]}; field=${repair["field"]}; " +
        "wedge_class=${repair["wedge_class"]}; prior=${repair["prior_value"] ?: "absent"}; " +
        "new=${repair["new_value"] ?: "absent"}",
    )
  }
}
