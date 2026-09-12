package skillbill.cli.goal

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairStatus

internal fun appendGoalResetSubtaskLines(builder: StringBuilder, subtasks: List<*>?) {
  subtasks.orEmpty().forEach { raw ->
    val subtask = raw as? Map<*, *> ?: return@forEach
    builder.append("  - ")
    builder.append("id=")
    builder.append(subtask["id"])
    builder.append("; status=")
    builder.append(subtask[SharedPayloadKeys.STATUS])
    builder.append("; workflow_id=")
    builder.append(subtask[SharedPayloadKeys.WORKFLOW_ID] ?: "none")
    builder.append("; commit_sha=")
    builder.append(subtask["commit_sha"] ?: "none")
    builder.append("; blocked_reason=")
    builder.append(subtask["blocked_reason"] ?: "none")
    builder.append("; last_resumable_step=")
    builder.append(subtask["last_resumable_step"] ?: "none")
    builder.append('\n')
  }
}

internal fun Map<String, Any?>.goalResetExitCode(): Int = if (this[SharedPayloadKeys.STATUS] == "ok") 0 else 1

internal fun GoalRunnerRepairResult.toGoalRepairCliMap(): Map<String, Any?> = linkedMapOf(
  SharedPayloadKeys.STATUS to status.wireValue,
  SharedPayloadKeys.ISSUE_KEY to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "refusal_reason" to refusalReason,
  "live_lease_workflow_id" to liveLeaseWorkflowId,
  "diagnoses" to diagnoses.map { diagnosis ->
    linkedMapOf(
      SharedPayloadKeys.SUBTASK_ID to diagnosis.subtaskId,
      SharedPayloadKeys.WORKFLOW_ID to diagnosis.workflowId,
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
      SharedPayloadKeys.SUBTASK_ID to repair.subtaskId,
      SharedPayloadKeys.WORKFLOW_ID to repair.workflowId,
      "wedge_class" to repair.wedgeClass.wireValue,
      "field" to repair.field,
      "prior_value" to repair.priorValue,
      "new_value" to repair.newValue,
    )
  },
)

internal fun Map<String, Any?>.goalRepairExitCode(): Int = when (this[SharedPayloadKeys.STATUS]) {
  GoalRunnerRepairStatus.HEALTHY.wireValue,
  GoalRunnerRepairStatus.REPAIRED.wireValue,
  -> 0
  GoalRunnerRepairStatus.INSPECTED.wireValue,
  GoalRunnerRepairStatus.OPERATOR_REQUIRED.wireValue,
  -> 2
  else -> 1
}

internal fun goalRepairText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload[SharedPayloadKeys.ISSUE_KEY]}")
  appendLine("status: ${payload[SharedPayloadKeys.STATUS]}")
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
      "  - subtask=${diagnosis[SharedPayloadKeys.SUBTASK_ID]}; " +
        "workflow_id=${diagnosis[SharedPayloadKeys.WORKFLOW_ID] ?: "none"}; " +
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
      "  - subtask=${repair[SharedPayloadKeys.SUBTASK_ID]}; field=${repair["field"]}; " +
        "wedge_class=${repair["wedge_class"]}; prior=${repair["prior_value"] ?: "absent"}; " +
        "new=${repair["new_value"] ?: "absent"}",
    )
  }
}

internal fun GoalRunnerOperatorDecisionResult.toGoalOperatorDecisionCliMap(): Map<String, Any?> = when (this) {
  is GoalRunnerOperatorDecisionResult.Recorded -> linkedMapOf(
    SharedPayloadKeys.STATUS to "ok",
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "parent_workflow_id" to parentWorkflowId,
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "decision" to decision,
  )
  is GoalRunnerOperatorDecisionResult.Rejected -> linkedMapOf(
    SharedPayloadKeys.STATUS to "rejected",
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "reason" to reason,
  )
}

internal fun Map<String, Any?>.goalOperatorDecisionExitCode(): Int =
  if (this[SharedPayloadKeys.STATUS] == "ok") 0 else 1

internal fun goalOperatorDecisionText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal: ${payload[SharedPayloadKeys.ISSUE_KEY]}")
  appendLine("status: ${payload[SharedPayloadKeys.STATUS]}")
  payload["parent_workflow_id"]?.let { appendLine("parent_workflow_id: $it") }
  payload[SharedPayloadKeys.SUBTASK_ID]?.let { appendLine("subtask_id: $it") }
  payload[SharedPayloadKeys.WORKFLOW_ID]?.let { appendLine("workflow_id: $it") }
  payload["decision"]?.let { appendLine("decision: $it") }
  payload["reason"]?.let { appendLine("reason: $it") }
  if (payload[SharedPayloadKeys.STATUS] == "ok") {
    appendLine("next: skill-bill goal resume ${payload[SharedPayloadKeys.ISSUE_KEY]} (consumes the recorded decision)")
  }
}
