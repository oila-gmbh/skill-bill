package skillbill.mcp.workflow

import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts

internal fun workflowSnapshotMcpMap(
  snapshot: WorkflowSnapshotView,
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): LinkedHashMap<String, Any?> = LinkedHashMap(WorkflowEngine.snapshotMap(snapshot)).apply {
  goalObservabilitySummaryFromArtifacts(snapshot.artifacts, goalObservabilityEventValidator)?.let { summary ->
    put("goal_observability", summary)
  }
  (snapshot.artifacts[GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY] as? Map<*, *>)?.let { event ->
    put("goal_progress", event)
  }
  (snapshot.artifacts[GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY] as? List<*>)?.lastOrNull()?.let { entry ->
    put("goal_attempt_ledger_latest", entry)
  }
}

internal fun GoalPlanningStatusSnapshot.toMcpMap(): Map<String, Any?> = linkedMapOf(
  "state" to state.wireValue,
  "shared_preplan_prepared" to sharedPreplanPrepared,
  "planned_subtask_count" to plannedSubtaskCount,
  "total_subtask_count" to totalSubtaskCount,
  "current_planning_subtask" to currentPlanningSubtaskId,
  "reason" to reason,
)

private fun goalObservabilitySummaryFromArtifacts(
  artifacts: Map<String, Any?>,
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): Map<String, Any?>? = goalObservabilityLatestEventFromArtifacts(artifacts, goalObservabilityEventValidator)
  ?.toCompactSummaryMap()
