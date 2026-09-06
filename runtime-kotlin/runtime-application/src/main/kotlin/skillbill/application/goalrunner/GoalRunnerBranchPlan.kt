package skillbill.application.goalrunner
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId

internal data class GoalRunnerBranchPlan(
  val branch: String,
  val baseBranch: String,
  val validateBase: Boolean,
)

fun DecompositionManifest.withAttemptedSubtask(subtaskId: SubtaskId): DecompositionManifest = copy(
  status = "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId && subtask.status in setOf("blocked", "pending")) {
      subtask.copy(status = "in_progress", blockedReason = null)
    } else {
      subtask
    }
  },
)

fun DecompositionManifest.withWorkflowId(subtaskId: SubtaskId, workflowId: WorkflowId): DecompositionManifest = copy(
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) subtask.copy(workflowId = workflowId) else subtask
  },
)

fun DecompositionManifest.knownWorkflowId(subtaskId: SubtaskId, outcome: GoalRunnerReconciledOutcome.Stop): WorkflowId? =
  outcome.workflowId ?: subtasks.firstOrNull { it.id == subtaskId }?.workflowId

fun DecompositionManifest.withCompletedSubtask(
  subtaskId: SubtaskId,
  outcome: GoalRunnerReconciledOutcome.Complete,
): DecompositionManifest {
  val updated = copy(
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
    subtasks = subtasks.map { subtask ->
      if (subtask.id == subtaskId) {
        subtask.copy(
          status = "complete",
          workflowId = outcome.workflowId,
          commitSha = outcome.commitSha,
          blockedReason = null,
          lastResumableStep = outcome.lastResumableStep,
        )
      } else {
        subtask
      }
    },
  )
  return if (updated.subtasks.all { it.status == "complete" || it.status == "skipped" }) {
    updated.copy(status = "complete")
  } else {
    updated.copy(status = "in_progress")
  }
}

fun DecompositionManifest.withStoppedSubtask(
  subtaskId: SubtaskId,
  outcome: GoalRunnerReconciledOutcome.Stop,
  knownWorkflowId: WorkflowId? = outcome.workflowId,
): DecompositionManifest = copy(
  status = "blocked",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "blocked",
        workflowId = knownWorkflowId ?: subtask.workflowId,
        commitSha = outcome.commitSha ?: subtask.commitSha,
        blockedReason = outcome.blockedReason,
        lastResumableStep = outcome.lastResumableStep,
      )
    } else {
      subtask
    }
  },
)

fun DecompositionManifest.withResumableSubtask(
  subtaskId: SubtaskId,
  outcome: GoalRunnerReconciledOutcome.Stop,
  knownWorkflowId: WorkflowId? = outcome.workflowId,
): DecompositionManifest = copy(
  status = "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "in_progress",
        workflowId = knownWorkflowId ?: subtask.workflowId,
        commitSha = outcome.commitSha ?: subtask.commitSha,
        blockedReason = null,
        lastResumableStep = outcome.lastResumableStep,
      )
    } else {
      subtask
    }
  },
)
