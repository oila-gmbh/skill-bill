package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerResetResult
import skillbill.application.model.GoalRunnerResetSnapshot
import skillbill.application.model.GoalRunnerResetSubtaskSnapshot
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.install.model.InstallAgent
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

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

  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? {
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return null
    val before = loaded.manifest.toResetSnapshot()
    val resetManifest = loaded.manifest.resetManifest(request.hard)
    val saved = manifestStore.save(loaded.copy(manifest = resetManifest), request.dbPathOverride)
    return GoalRunnerResetResult(
      issueKey = saved.manifest.issueKey,
      mode = if (request.hard) "hard" else "soft",
      parentWorkflowId = saved.parentWorkflowId,
      before = before,
      after = saved.manifest.toResetSnapshot(),
    )
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

private fun DecompositionManifest.resetManifest(hard: Boolean): DecompositionManifest {
  val resetSubtasks = subtasks.map { subtask ->
    val preserveOutcome = !hard && subtask.status in setOf("complete", "skipped")
    if (preserveOutcome) {
      subtask.copy(
        blockedReason = null,
        lastResumableStep = null,
      )
    } else {
      subtask.copy(
        status = "pending",
        branch = null,
        commitSha = null,
        workflowId = null,
        blockedReason = null,
        lastResumableStep = null,
      )
    }
  }
  return copy(
    currentSubtaskIntent = restartIntent(resetSubtasks),
    subtasks = resetSubtasks,
  ).withParentStatus()
}

private fun restartIntent(subtasks: List<DecompositionSubtask>): CurrentSubtaskIntent {
  if (subtasks.all { it.status in setOf("complete", "skipped") }) {
    return CurrentSubtaskIntent(subtaskId = 0, action = "none")
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
    action = if (nextRunnable == null) "none" else "start",
  )
}

private fun DecompositionManifest.toResetSnapshot(): GoalRunnerResetSnapshot = GoalRunnerResetSnapshot(
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
