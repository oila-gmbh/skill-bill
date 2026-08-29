package skillbill.application.goalrunner

import skillbill.application.decomposition.withParentStatus
import skillbill.application.featuretask.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.application.featuretask.pruneCompletedSubtaskCheckpointRefs
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

internal fun reconcileGoalManifest(
  manifest: DecompositionManifest,
  dbPathOverride: String?,
  authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
): DecompositionManifest {
  val context = GoalManifestReconciliationContext(
    issueKey = manifest.issueKey,
    dbPathOverride = dbPathOverride,
    authoritativeOutcomes = authoritativeOutcomes,
    acceptances = acceptances,
    outcomeStore = outcomeStore,
  )
  return manifest.copy(subtasks = manifest.subtasks.map { subtask -> context.reconcile(subtask) })
    .withParentStatus()
    .withDerivedCurrentIntent()
}

internal fun pruneEligibleCheckpointRefsForManifest(
  manifest: DecompositionManifest,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  record: (String) -> Unit,
) {
  manifest.subtasks.forEach { subtask ->
    if (subtask.status == "complete" && !subtask.commitSha.isNullOrBlank()) {
      pruneCompletedSubtaskCheckpointRefs(
        gitOperations = gitOperations,
        repoRoot = repoRoot,
        request = FeatureTaskRuntimeCheckpointRefPruneRequest(
          issueKey = manifest.issueKey,
          subtaskId = subtask.id.toString(),
          manifestCommitSha = subtask.commitSha,
          featureBranch = manifest.featureBranch,
        ),
        record = record,
      )
    }
  }
}

private data class GoalManifestReconciliationContext(
  val issueKey: String,
  val dbPathOverride: String?,
  val authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  val acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  fun reconcile(subtask: DecompositionSubtask): DecompositionSubtask {
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
    val outcome = workflowId?.let { id -> preferredOutcome(subtask, id) }
    // Runtime evidence wins: an acceptance only speaks for a subtask the runtime never carried to
    // completion itself, so it can never downgrade or overwrite a genuine COMPLETE outcome.
    acceptances[subtask.id]
      ?.takeIf { outcome?.status != GoalRunnerTerminalStatus.COMPLETE }
      ?.let { acceptance ->
        return subtask.copy(
          status = "complete",
          commitSha = acceptance.commitSha,
          blockedReason = null,
          lastResumableStep = null,
        )
      }

    val staleRetryOutcome = workflowId != null &&
      outcome?.workflowId == workflowId &&
      outcome.status != GoalRunnerTerminalStatus.COMPLETE &&
      outcomeStore.progress(workflowId, dbPathOverride)?.workflowStatus == "running"
    return if (staleRetryOutcome) {
      subtask.copy(status = "in_progress", blockedReason = null)
    } else if (outcome == null || shouldPreserveCompletedSubtask(subtask, outcome)) {
      subtask
    } else {
      val status = outcome.toManifestStatus()
      subtask.copy(
        status = status,
        workflowId = outcome.workflowId.takeIf(String::isNotBlank) ?: subtask.workflowId,
        commitSha = outcome.commitSha ?: subtask.commitSha,
        blockedReason = outcome.blockedReason
          ?.takeIf { status == "blocked" }
          ?: subtask.blockedReason.takeIf { status == "blocked" },
        lastResumableStep = outcome.lastResumableStep ?: subtask.lastResumableStep,
      )
    }
  }

  private fun preferredOutcome(subtask: DecompositionSubtask, workflowId: String): GoalRunnerStoredOutcome? =
    authoritativeOutcomes[subtask.id]
      ?.takeIf { outcome -> canApplyAuthoritativeOutcome(subtask, workflowId, outcome) }
      ?: outcomeStore.terminalOutcome(
        workflowId = workflowId,
        issueKey = issueKey,
        subtaskId = subtask.id,
        dbPathOverride = dbPathOverride,
      )
}

private fun canApplyAuthoritativeOutcome(
  subtask: DecompositionSubtask,
  workflowId: String,
  outcome: GoalRunnerStoredOutcome,
): Boolean {
  val resetPendingSubtask = subtask.status == "pending" && subtask.workflowId.isNullOrBlank()
  if (resetPendingSubtask && outcome.status != GoalRunnerTerminalStatus.COMPLETE) {
    return false
  }
  // Do not let non-complete sibling outcomes overwrite an active retry workflow.
  val nonCompleteSibling = outcome.workflowId != workflowId && outcome.status != GoalRunnerTerminalStatus.COMPLETE
  return subtask.status != "in_progress" || !nonCompleteSibling
}

private fun shouldPreserveCompletedSubtask(subtask: DecompositionSubtask, outcome: GoalRunnerStoredOutcome): Boolean =
  subtask.status == "complete" &&
    !subtask.commitSha.isNullOrBlank() &&
    outcome.status != GoalRunnerTerminalStatus.COMPLETE

private fun GoalRunnerStoredOutcome.toManifestStatus(): String = when (status) {
  GoalRunnerTerminalStatus.COMPLETE -> "complete"
  // A crash-reconciled row is resumable, not blocked: keep the subtask in_progress so resume continues.
  GoalRunnerTerminalStatus.RECONCILABLE -> "in_progress"
  // A paused child awaits the operator decision and stays resumable, so it is not blocked either.
  GoalRunnerTerminalStatus.PAUSED -> "in_progress"
  GoalRunnerTerminalStatus.BLOCKED,
  GoalRunnerTerminalStatus.FAILED,
  GoalRunnerTerminalStatus.TIMEOUT,
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
  -> "blocked"
}

private fun DecompositionManifest.withDerivedCurrentIntent(): DecompositionManifest {
  val nextIntent = subtasks.firstOrNull { it.status == "blocked" }?.let { blocked ->
    CurrentSubtaskIntent(subtaskId = blocked.id, action = "blocked")
  } ?: subtasks.firstOrNull { it.status == "in_progress" }?.let { inProgress ->
    CurrentSubtaskIntent(subtaskId = inProgress.id, action = "resume")
  } ?: firstRunnablePendingSubtask()?.let { pending ->
    CurrentSubtaskIntent(subtaskId = pending.id, action = "start")
  } ?: CurrentSubtaskIntent(subtaskId = 0, action = "complete")
  return copy(currentSubtaskIntent = nextIntent)
}

private fun DecompositionManifest.firstRunnablePendingSubtask(): DecompositionSubtask? {
  val subtasksById = subtasks.associateBy(DecompositionSubtask::id)
  return subtasks.firstOrNull { subtask ->
    subtask.status == "pending" && subtask.dependencies.all { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId]
      dependencySubtask?.status in setOf("complete", "skipped") || (dependency.optional && dependency.skipped)
    }
  } ?: subtasks.firstOrNull { it.status == "pending" }
}
