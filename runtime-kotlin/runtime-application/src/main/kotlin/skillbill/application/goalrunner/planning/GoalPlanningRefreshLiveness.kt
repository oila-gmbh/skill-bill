package skillbill.application.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.time.Clock
import java.time.Instant

fun interface GoalPlanningRefreshLiveness {
  fun resolve(state: GoalRunnerManifestState, dbPathOverride: String?): ExecutionLiveness

  companion object {
    val IDLE: GoalPlanningRefreshLiveness = GoalPlanningRefreshLiveness { _, _ -> ExecutionLiveness.IDLE }
  }
}

@Inject
class ChildAwareGoalPlanningRefreshLiveness(
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  private val clock: Clock,
) : GoalPlanningRefreshLiveness {
  override fun resolve(state: GoalRunnerManifestState, dbPathOverride: String?): ExecutionLiveness {
    val currentSubtask = state.manifest.subtasks.firstOrNull { subtask ->
      subtask.id == state.manifest.currentSubtaskIntent.subtaskId
    }
    return resolveChildExecutionLiveness(currentSubtask, dbPathOverride, phaseRecorder, clock)
  }
}

/**
 * Child-workflow liveness only. When no child workflow id is selected, returns IDLE — the parent
 * execution lease is held by the owning prepare() and must not block in-run refresh.
 */
internal fun resolveChildExecutionLiveness(
  currentSubtask: DecompositionSubtask?,
  dbPathOverride: String?,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  clock: Clock,
): ExecutionLiveness {
  val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank) ?: return ExecutionLiveness.IDLE
  return runCatching {
    if (phaseRecorder.existingWorkflowMode(workflowId, dbPathOverride) != FeatureTaskWorkflowMode.RUNTIME) {
      ExecutionLiveness.UNKNOWN
    } else {
      val ownership = phaseRecorder.workerOwnership(workflowId, dbPathOverride)
      if (ownership != null && Instant.parse(ownership.expiresAt).isAfter(clock.instant())) {
        ExecutionLiveness.LIVE
      } else {
        ExecutionLiveness.IDLE
      }
    }
  }.getOrDefault(ExecutionLiveness.UNKNOWN)
}

internal fun refuseRefreshReason(issueKey: String, liveness: ExecutionLiveness): String? = when (liveness) {
  ExecutionLiveness.LIVE ->
    "Goal '$issueKey' is live; refuse shared-preplan refresh while the current child run is active."
  ExecutionLiveness.UNKNOWN ->
    "Goal '$issueKey' has unknown execution liveness; refuse shared-preplan refresh."
  ExecutionLiveness.IDLE -> null
}
