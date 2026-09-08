package skillbill.ports.goalrunner.persistence
import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome

@OpenBoundaryMap("Goal runner supervision event durable artifact map")
fun GoalRunnerSupervisionEvent.toArtifactsMap(): Map<String, Any?> = linkedMapOf(
  "phase" to phase,
  "reason" to reason,
  "continuation_mode" to continuationMode,
  "process_state" to processState,
  "workflow_id" to workflowId,
  "step_id" to stepId,
  "last_durable_progress" to lastDurableProgress,
  "last_workflow_snapshot_at" to lastWorkflowSnapshotAt,
  "last_file_activity_at" to lastFileActivityAt,
  "last_output_at" to lastOutputAt,
)

const val WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY = "goal_worker_subtask_request_outcomes"
const val WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT = 50

@OpenBoundaryMap("Goal worker subtask request outcome durable artifact map")
fun GoalRunnerWorkerSubtaskRequestOutcome.toArtifactMap(): Map<String, Any?> = when (this) {
  is GoalRunnerWorkerSubtaskRequestOutcome.Accepted -> linkedMapOf(
    "status" to "accepted",
    "source_stream" to sourceStream,
    "request" to request.toArtifactMap(),
    "subtask_id" to subtask.id,
    "spec_path" to subtask.specPath,
  )
  is GoalRunnerWorkerSubtaskRequestOutcome.Queued -> linkedMapOf(
    "status" to "queued",
    "source_stream" to sourceStream,
    "request" to request.toArtifactMap(),
    "reason" to reason,
  )
  is GoalRunnerWorkerSubtaskRequestOutcome.Rejected -> linkedMapOf(
    "status" to "rejected",
    "source_stream" to sourceStream,
    "reason" to reason.name.lowercase(),
    "message" to message,
  )
  is GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation -> linkedMapOf(
    "status" to "requires_operator_confirmation",
    "source_stream" to sourceStream,
    "request" to request.toArtifactMap(),
    "reason" to reason,
  )
}

@OpenBoundaryMap("Goal worker subtask request durable artifact map")
fun GoalRunnerWorkerSubtaskRequest.toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "name" to name,
  "spec_path" to specPath,
  "rationale" to rationale,
  "depends_on_subtask_ids" to dependsOnSubtaskIds,
  "requires_operator_confirmation" to requiresOperatorConfirmation,
).filterValues { value -> value != null }

fun Any?.asGoalRunnerIntOrNull(): Int? = when (this) {
  is Int -> this
  is Number -> toInt()
  is String -> toIntOrNull()
  else -> null
}
