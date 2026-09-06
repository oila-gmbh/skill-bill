package skillbill.application.goalrunner

import skillbill.workflow.engine.model.WorkflowId
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.workflow.decomposition.model.SubtaskId

class GoalRunnerValidationQualityPendingState(
  private val manifestStore: GoalRunnerManifestStore,
) {
  private var parentWorkflowId: WorkflowId = WorkflowId("")

  fun bind(parentWorkflowId: WorkflowId) {
    this.parentWorkflowId = parentWorkflowId
  }

  private fun control(): GoalRunnerControlState = manifestStore.controlState(parentWorkflowId)

  private fun update(transform: (GoalRunnerControlState) -> GoalRunnerControlState) {
    val current = control()
    manifestStore.persistControlState(parentWorkflowId, transform(current))
  }

  fun validationQualityRetryCount(subtaskId: SubtaskId): Int =
    control().validationQualityRetriesBySubtask[subtaskId] ?: 0

  fun incrementValidationQualityRetry(subtaskId: SubtaskId): Int {
    val next = validationQualityRetryCount(subtaskId) + 1
    update { state ->
      state.copy(validationQualityRetriesBySubtask = state.validationQualityRetriesBySubtask + (subtaskId to next))
    }
    return next
  }

  fun storePendingReAttemptCause(subtaskId: SubtaskId, cause: String) {
    update { state ->
      state.copy(pendingReAttemptCauseBySubtask = state.pendingReAttemptCauseBySubtask + (subtaskId to cause))
    }
  }

  fun takePendingReAttemptCause(subtaskId: SubtaskId): String? {
    val current = control()
    val cause = current.pendingReAttemptCauseBySubtask[subtaskId] ?: return null
    update { state ->
      state.copy(pendingReAttemptCauseBySubtask = state.pendingReAttemptCauseBySubtask - subtaskId)
    }
    return cause
  }

  fun storePendingCausingLoopEntry(subtaskId: SubtaskId, entry: String) {
    update { state ->
      state.copy(pendingCausingLoopEntryBySubtask = state.pendingCausingLoopEntryBySubtask + (subtaskId to entry))
    }
  }

  fun takePendingCausingLoopEntry(subtaskId: SubtaskId): String? {
    val current = control()
    val entry = current.pendingCausingLoopEntryBySubtask[subtaskId] ?: return null
    update { state ->
      state.copy(pendingCausingLoopEntryBySubtask = state.pendingCausingLoopEntryBySubtask - subtaskId)
    }
    return entry
  }
}
