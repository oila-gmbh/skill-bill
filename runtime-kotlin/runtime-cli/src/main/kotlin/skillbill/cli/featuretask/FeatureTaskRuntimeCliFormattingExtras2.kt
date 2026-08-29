package skillbill.cli.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusProjection

internal fun runtimeRunText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["issue_key"]}")
  appendLine("workflow_id: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("feature_size: ${payload["feature_size"]}")
  appendLine("resolved_branch: ${payload["resolved_branch"] ?: "none"}")
  appendLine("completed_phases: ${(payload["completed_phases"] as? List<*>).orEmpty().joinToString()}")
  payload["last_incomplete_phase"]?.let { appendLine("last_incomplete_phase: $it") }
  payload["blocked_reason"]?.let { appendLine("blocked_reason: $it") }
  (payload["subtask_outcome"] as? Map<*, *>)?.let { outcome -> appendSubtaskOutcome(outcome) }
  payload["reason"]?.let { appendLine("decomposition_reason: $it") }
  payload["subtask_count"]?.let { appendLine("subtask_count: $it") }
  payload["parent_spec_path"]?.let { appendLine("parent_spec_path: $it") }
  payload["decomposition_manifest_path"]?.let { appendLine("decomposition_manifest_path: $it") }
  (payload["subtask_spec_paths"] as? List<*>).orEmpty().forEach { appendLine("subtask_spec_path: $it") }
  payload["guidance"]?.let { appendLine("guidance: $it") }
}

internal fun StringBuilder.appendSubtaskOutcome(outcome: Map<*, *>) {
  appendLine("subtask_outcome:")
  appendLine("  issue_key: ${outcome["issue_key"]}")
  appendLine("  subtask_id: ${outcome["subtask_id"]}")
  appendLine("  status: ${outcome["status"]}")
  appendLine("  commit_sha: ${outcome["commit_sha"] ?: "none"}")
  appendLine("  workflow_id: ${outcome["workflow_id"]}")
  appendLine("  last_resumable_step: ${outcome["last_resumable_step"]}")
  outcome["finalizing_agent_id"]?.let { appendLine("  finalizing_agent_id: $it") }
  (outcome["participating_agent_ids"] as? List<*>)?.takeIf { it.isNotEmpty() }
    ?.let { appendLine("  participating_agent_ids: ${it.joinToString()}") }
  outcome["blocked_reason"]?.let { appendLine("  blocked_reason: $it") }
}

internal fun FeatureTaskRuntimeStatusProjection?.toRuntimeStatusCliMap(workflowId: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      "status" to "ok",
      "workflow_id" to it.workflowId,
      "feature_size" to it.featureSize,
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "current_phase" to it.currentPhaseId,
      "resolved_branch" to it.resolvedBranch,
      "finalizing_agent_id" to it.finalizingAgentId,
      "gate_run_count" to it.gateRunCount,
      "audit_repair" to it.auditRepair?.let { progress ->
        linkedMapOf(
          "first_pass_convergence" to progress.firstPassConvergence,
          "audit_gap_iteration_count" to progress.auditGapIterationCount,
        )
      },
      "degraded_diagnostic" to it.degradedDiagnostic?.let { degraded ->
        linkedMapOf(
          "count" to degraded.count,
          "failure_class" to degraded.failureClass,
          "phase_id" to degraded.phaseId,
          "attempt" to degraded.attempt,
        )
      },
      "decompose_terminal" to it.decomposeTerminal?.let { terminal ->
        linkedMapOf(
          "reason" to terminal.reason,
          "parent_spec_path" to terminal.parentSpecPath,
          "decomposition_manifest_path" to terminal.decompositionManifestPath,
          "subtask_spec_paths" to terminal.subtaskSpecPaths,
          "subtask_count" to terminal.subtaskCount,
          "guidance" to DECOMPOSE_GUIDANCE,
        )
      },
      "phases" to it.phases.map(FeatureTaskRuntimePhaseStatus::toRuntimePhaseStatusCliMap),
    )
  } ?: linkedMapOf(
    "status" to "not_found",
    "workflow_id" to workflowId,
    "feature_size" to null,
    "complete_count" to 0,
    "pending_count" to 0,
    "blocked_count" to 0,
    "current_phase" to null,
    "resolved_branch" to null,
    "finalizing_agent_id" to null,
    "audit_repair" to null,
    "degraded_diagnostic" to null,
    "decompose_terminal" to null,
    "phases" to emptyList<Map<String, Any?>>(),
  )

internal fun FeatureTaskRuntimePhaseStatus.toRuntimePhaseStatusCliMap(): Map<String, Any?> = linkedMapOf(
  "phase_id" to phaseId,
  "status" to status,
  "attempt_count" to attemptCount,
  "resolved_agent_id" to resolvedAgentId,
  "execution_origin" to executionOrigin,
  "continuation_kind" to continuationKind,
  "finished" to finished,
)

internal fun Map<String, Any?>.runtimeStatusExitCode(): Int = if (this["status"] == "ok") 0 else 1

internal fun runtimeStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("feature_size: ${payload["feature_size"] ?: "unknown"}")
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_phase: ${payload["current_phase"] ?: "none"}")
  appendLine("resolved_branch: ${payload["resolved_branch"] ?: "none"}")
  appendLine("finalizing_agent: ${payload["finalizing_agent_id"] ?: "none"}")
  (payload["audit_repair"] as? Map<*, *>)?.let { progress ->
    appendLine("audit_first_pass_convergence: ${progress["first_pass_convergence"]}")
    appendLine("audit_recurring_gap_count: ${progress["recurring_gap_count"]}")
    appendLine("audit_new_gap_count: ${progress["new_gap_count"]}")
    appendLine("audit_attempted_repair_item_count: ${progress["attempted_repair_item_count"]}")
    appendLine("audit_resolved_repair_item_count: ${progress["resolved_repair_item_count"]}")
    appendLine("audit_gap_iteration_count: ${progress["audit_gap_iteration_count"]}")
  }
  (payload["degraded_diagnostic"] as? Map<*, *>)?.let { degraded ->
    appendLine("degraded_diagnostic_count: ${degraded["count"]}")
    appendLine("degraded_diagnostic_failure_class: ${degraded["failure_class"]}")
    appendLine("degraded_diagnostic_phase: ${degraded["phase_id"]}")
    appendLine("degraded_diagnostic_attempt: ${degraded["attempt"]}")
  }
  (payload["decompose_terminal"] as? Map<*, *>)?.let { terminal ->
    appendLine("decomposition_reason: ${terminal["reason"]}")
    appendLine("subtask_count: ${terminal["subtask_count"]}")
    appendLine("parent_spec_path: ${terminal["parent_spec_path"]}")
    appendLine("decomposition_manifest_path: ${terminal["decomposition_manifest_path"]}")
    (terminal["subtask_spec_paths"] as? List<*>).orEmpty().forEach { appendLine("subtask_spec_path: $it") }
    appendLine("guidance: ${terminal["guidance"]}")
  }
  (payload["phases"] as? List<*>).orEmpty().forEach { rawPhase ->
    val phase = rawPhase as? Map<*, *> ?: return@forEach
    appendLine(
      "phase: id=${phase["phase_id"]} status=${phase["status"]} attempt=${phase["attempt_count"]} " +
        "agent=${phase["resolved_agent_id"] ?: "none"} " +
        "origin=${phase["execution_origin"] ?: "none"} finished=${phase["finished"]}",
    )
  }
}

internal const val DECOMPOSE_GUIDANCE: String =
  "Work the first subtask first, then continue through the ordered spec_subtask_*.md files."
