package skillbill.cli

import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.WorkflowSnapshotView
import skillbill.workflow.model.goalObservabilityLatestEventFromArtifacts

internal fun workflowSnapshotCliMap(
  snapshot: WorkflowSnapshotView,
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): LinkedHashMap<String, Any?> = LinkedHashMap(WorkflowEngine.snapshotMap(snapshot)).apply {
  goalObservabilitySummaryFromArtifacts(snapshot.artifacts, goalObservabilityEventValidator)?.let { summary ->
    put("goal_observability", summary)
  }
}

private fun goalObservabilitySummaryFromArtifacts(
  artifacts: Map<String, Any?>,
  goalObservabilityEventValidator: GoalObservabilityEventValidator,
): Map<String, Any?>? = goalObservabilityLatestEventFromArtifacts(artifacts, goalObservabilityEventValidator)
  ?.toCompactSummaryMap()
