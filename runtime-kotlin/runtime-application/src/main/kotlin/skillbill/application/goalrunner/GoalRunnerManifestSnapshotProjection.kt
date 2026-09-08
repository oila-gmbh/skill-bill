package skillbill.application.goalrunner

import skillbill.application.decomposition.withParentStatus
import skillbill.application.goalrunner.model.GoalRunnerResetSnapshot
import skillbill.application.goalrunner.model.GoalRunnerResetSubtaskSnapshot
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.ports.goalrunner.runner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask

const val NO_CURRENT_SUBTASK_ID = 0
const val NO_CURRENT_SUBTASK_ACTION = "none"
const val SUBTASK_ACTION_START = "start"
const val SUBTASK_ACTION_RESUME = "resume"
const val SUBTASK_STATUS_IN_PROGRESS = "in_progress"
const val SUBTASK_STATUS_PENDING = "pending"
const val SUBTASK_STATUS_COMPLETE = "complete"
const val SUBTASK_STATUS_SKIPPED = "skipped"

fun DecompositionManifest.isAtUnlaunchedBoundary(): Boolean {
  val currentSubtask = subtasks.firstOrNull { it.id == currentSubtaskIntent.subtaskId }
  val activeChildExists = subtasks.any { subtask ->
    subtask.status == SUBTASK_STATUS_IN_PROGRESS && subtask.workflowId?.isNotBlank() == true
  }
  val unselected = currentSubtaskIntent.subtaskId == NO_CURRENT_SUBTASK_ID &&
    currentSubtaskIntent.action == NO_CURRENT_SUBTASK_ACTION
  val selectedButNotLaunched = currentSubtask?.let { subtask ->
    subtask.status == SUBTASK_STATUS_PENDING &&
      subtask.workflowId.isNullOrBlank() &&
      currentSubtaskIntent.action == SUBTASK_ACTION_START
  } == true
  return !activeChildExists && (unselected || selectedButNotLaunched)
}

fun DecompositionManifest.resetManifest(hard: Boolean): DecompositionManifest {
  val freshReset: (DecompositionSubtask) -> DecompositionSubtask = { subtask ->
    subtask.copy(
      status = "pending",
      branch = null,
      commitSha = null,
      workflowId = null,
      blockedReason = null,
      lastResumableStep = null,
    )
  }
  val resetSubtasks = subtasks.map { subtask ->
    when {
      hard -> freshReset(subtask)
      subtask.status in setOf("complete", "skipped") -> subtask.copy(
        blockedReason = null,
        lastResumableStep = null,
      )
      !subtask.workflowId.isNullOrBlank() -> subtask.copy(
        status = "in_progress",
        blockedReason = null,
      )
      else -> freshReset(subtask)
    }
  }
  return copy(
    currentSubtaskIntent = restartIntent(resetSubtasks),
    subtasks = resetSubtasks,
  ).withParentStatus()
}

fun restartIntent(subtasks: List<DecompositionSubtask>): CurrentSubtaskIntent {
  if (subtasks.all { it.status in setOf("complete", "skipped") }) {
    return CurrentSubtaskIntent(subtaskId = 0, action = "complete")
  }
  subtasks.firstOrNull { it.status == "in_progress" }?.let { resumable ->
    return CurrentSubtaskIntent(subtaskId = resumable.id, action = "resume")
  }
  val subtasksById = subtasks.associateBy(DecompositionSubtask::id)
  val nextRunnable = subtasks.firstOrNull { subtask ->
    subtask.status == "pending" && subtask.dependencies.all { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId]
      dependencySubtask?.status in setOf("complete", "skipped") || (dependency.optional && dependency.skipped)
    }
  } ?: subtasks.firstOrNull { it.status == "pending" }
  return CurrentSubtaskIntent(
    subtaskId = nextRunnable?.id ?: 0,
    action = if (nextRunnable == null) "complete" else "start",
  )
}

fun replanIntent(subtask: DecompositionSubtask): CurrentSubtaskIntent {
  val action = when {
    subtask.status == SUBTASK_STATUS_IN_PROGRESS || !subtask.workflowId.isNullOrBlank() -> SUBTASK_ACTION_RESUME
    else -> SUBTASK_ACTION_START
  }
  return CurrentSubtaskIntent(subtaskId = subtask.id, action = action)
}

fun DecompositionManifest.toResetSnapshot(): GoalRunnerResetSnapshot = GoalRunnerResetSnapshot(
  status = status,
  currentSubtaskId = currentSubtaskIntent.subtaskId.takeIf { it > 0 },
  currentAction = currentSubtaskIntent.action,
  subtasks = subtasks.map { subtask ->
    GoalRunnerResetSubtaskSnapshot(
      id = subtask.id,
      status = subtask.status,
      branch = subtask.branch,
      workflowId = subtask.workflowId,
      commitSha = subtask.commitSha,
      blockedReason = subtask.blockedReason,
      lastResumableStep = subtask.lastResumableStep,
    )
  },
)

fun GoalObservabilityProgressEvent.toStatusMap(): Map<String, Any?> = linkedMapOf(
  "issue_key" to issueKey,
  "subtask_id" to subtaskId,
  "workflow_phase" to workflowPhase,
  "worker_role" to workerRole,
  "liveness_class" to livenessClass,
  "activity_summary" to activitySummary,
  "sequence_number" to sequenceNumber,
  "timestamp" to timestamp,
)

fun Map<Int, GoalRunnerOutOfBandAcceptance>.toAcceptedSubtasks(): List<GoalRunnerAcceptedSubtask> =
  values.sortedBy(GoalRunnerOutOfBandAcceptance::subtaskId).map { acceptance ->
    GoalRunnerAcceptedSubtask(
      subtaskId = acceptance.subtaskId,
      commitSha = acceptance.commitSha,
      reason = acceptance.reason,
      acceptedAt = acceptance.acceptedAt,
    )
  }
