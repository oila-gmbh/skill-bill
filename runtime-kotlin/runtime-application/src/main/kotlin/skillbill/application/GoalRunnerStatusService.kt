package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.install.model.InstallAgent
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest

@Inject
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    val effectiveAgent = request.configuredAgentOverrideId
      ?.let { InstallAgent.fromNormalizedId(it, label = "configuredAgentOverrideId") }
      ?: InstallAgent.fromNormalizedId(request.invokedAgentId, label = "invokedAgentId")
    return manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?.manifest
      ?.let { loadedManifest ->
        val manifest = loadedManifest.withTerminalCurrentSubtask(request)
        val currentSubtask = manifest.subtasks.firstOrNull { subtask ->
          subtask.id == manifest.currentSubtaskIntent.subtaskId
        }
        val progress = currentSubtask
          ?.workflowId
          ?.takeIf(String::isNotBlank)
          ?.let { workflowId -> outcomeStore.progress(workflowId, request.dbPathOverride) }
        GoalRunnerStatusProjector.project(
          manifest = manifest,
          activeAgent = effectiveAgent.id,
          currentStepOverride = progress?.currentStepId,
          latestLivenessSignal = progress?.latestLivenessSignal,
        )
      }
  }

  private fun DecompositionManifest.withTerminalCurrentSubtask(
    request: GoalRunnerStatusRequest,
  ): DecompositionManifest {
    val currentSubtask = subtasks.firstOrNull { subtask -> subtask.id == currentSubtaskIntent.subtaskId }
    val outcome = currentSubtask
      ?.workflowId
      ?.takeIf(String::isNotBlank)
      ?.let { workflowId ->
        outcomeStore.terminalOutcome(
          workflowId = workflowId,
          issueKey = issueKey,
          subtaskId = currentSubtask.id,
          dbPathOverride = request.dbPathOverride,
        )
      }
    return if (currentSubtask == null || outcome == null) {
      this
    } else {
      when (outcome.status) {
        GoalRunnerTerminalStatus.COMPLETE -> withStatusOutcome(currentSubtask.id, "complete", outcome)
        GoalRunnerTerminalStatus.BLOCKED,
        GoalRunnerTerminalStatus.FAILED,
        GoalRunnerTerminalStatus.TIMEOUT,
        GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
        -> withStatusOutcome(currentSubtask.id, "blocked", outcome)
      }
    }
  }

  private fun DecompositionManifest.withStatusOutcome(
    subtaskId: Int,
    status: String,
    outcome: GoalRunnerStoredOutcome,
  ): DecompositionManifest {
    val updatedSubtasks = subtasks.map { subtask ->
      if (subtask.id == subtaskId) {
        subtask.copy(
          status = status,
          workflowId = outcome.workflowId.takeIf(String::isNotBlank) ?: subtask.workflowId,
          commitSha = outcome.commitSha ?: subtask.commitSha,
          blockedReason = outcome.blockedReason.takeIf { status == "blocked" },
          lastResumableStep = outcome.lastResumableStep ?: subtask.lastResumableStep,
        )
      } else {
        subtask
      }
    }
    val parentStatus = when {
      updatedSubtasks.all { it.status in setOf("complete", "skipped") } -> "complete"
      updatedSubtasks.any { it.status == "blocked" } -> "blocked"
      updatedSubtasks.any { it.status in setOf("in_progress", "complete", "skipped") } -> "in_progress"
      else -> "pending"
    }
    val intent = when (status) {
      "complete" -> CurrentSubtaskIntent(subtaskId = 0, action = "none")
      else -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked")
    }
    return copy(status = parentStatus, currentSubtaskIntent = intent, subtasks = updatedSubtasks)
  }
}
