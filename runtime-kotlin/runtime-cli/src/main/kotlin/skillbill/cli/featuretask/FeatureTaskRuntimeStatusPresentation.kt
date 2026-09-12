package skillbill.cli.featuretask

import skillbill.contracts.SharedPayloadKeys

import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimeStatusProjection

internal fun FeatureTaskRuntimeStatusProjection?.toRuntimeStatusCliMap(workflowId: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.STATUS to "ok",
      SharedPayloadKeys.WORKFLOW_ID to it.workflowId,
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
          SharedPayloadKeys.PHASE_ID to degraded.phaseId,
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
    SharedPayloadKeys.STATUS to "not_found",
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
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
  SharedPayloadKeys.PHASE_ID to phaseId,
  SharedPayloadKeys.STATUS to status,
  "attempt_count" to attemptCount,
  "resolved_agent_id" to resolvedAgentId,
  "execution_origin" to executionOrigin,
  "continuation_kind" to continuationKind,
  "finished" to finished,
)

internal fun Map<String, Any?>.runtimeStatusExitCode(): Int = if (this[SharedPayloadKeys.STATUS] == "ok") 0 else 1

internal fun runtimeStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload[SharedPayloadKeys.WORKFLOW_ID]}")
  appendLine("status: ${payload[SharedPayloadKeys.STATUS]}")
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
    appendLine("degraded_diagnostic_phase: ${degraded[SharedPayloadKeys.PHASE_ID]}")
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
      "phase: id=${phase[SharedPayloadKeys.PHASE_ID]} status=${phase[SharedPayloadKeys.STATUS]} attempt=${phase["attempt_count"]} " +
        "agent=${phase["resolved_agent_id"] ?: "none"} " +
        "origin=${phase["execution_origin"] ?: "none"} finished=${phase["finished"]}",
    )
  }
}
