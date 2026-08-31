package skillbill.application.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore

internal class GoalRunnerValidationQualityPendingState(
  private val manifestStore: GoalRunnerManifestStore,
) {
  private var parentWorkflowId: String = ""
  private var dbPathOverride: String? = null

  fun bind(parentWorkflowId: String, dbPathOverride: String?) {
    this.parentWorkflowId = parentWorkflowId
    this.dbPathOverride = dbPathOverride
  }

  private fun control(): GoalRunnerControlState = manifestStore.controlState(parentWorkflowId, dbPathOverride)

  private fun update(transform: (GoalRunnerControlState) -> GoalRunnerControlState) {
    val current = control()
    manifestStore.persistControlState(parentWorkflowId, transform(current), dbPathOverride)
  }

  fun validationQualityRetryCount(subtaskId: Int): Int = control().validationQualityRetriesBySubtask[subtaskId] ?: 0

  fun incrementValidationQualityRetry(subtaskId: Int): Int {
    val next = validationQualityRetryCount(subtaskId) + 1
    update { state ->
      state.copy(validationQualityRetriesBySubtask = state.validationQualityRetriesBySubtask + (subtaskId to next))
    }
    return next
  }

  fun storePendingReAttemptCause(subtaskId: Int, cause: String) {
    update { state ->
      state.copy(pendingReAttemptCauseBySubtask = state.pendingReAttemptCauseBySubtask + (subtaskId to cause))
    }
  }

  fun takePendingReAttemptCause(subtaskId: Int): String? {
    val current = control()
    val cause = current.pendingReAttemptCauseBySubtask[subtaskId] ?: return null
    update { state ->
      state.copy(pendingReAttemptCauseBySubtask = state.pendingReAttemptCauseBySubtask - subtaskId)
    }
    return cause
  }

  fun storePendingCausingLoopEntry(subtaskId: Int, entry: String) {
    update { state ->
      state.copy(pendingCausingLoopEntryBySubtask = state.pendingCausingLoopEntryBySubtask + (subtaskId to entry))
    }
  }

  fun takePendingCausingLoopEntry(subtaskId: Int): String? {
    val current = control()
    val entry = current.pendingCausingLoopEntryBySubtask[subtaskId] ?: return null
    update { state ->
      state.copy(pendingCausingLoopEntryBySubtask = state.pendingCausingLoopEntryBySubtask - subtaskId)
    }
    return entry
  }
}
