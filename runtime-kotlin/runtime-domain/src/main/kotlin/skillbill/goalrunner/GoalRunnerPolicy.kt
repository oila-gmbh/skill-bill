package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAction
import skillbill.goalrunner.model.GoalRunnerSubtaskDecision
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

object GoalRunnerPlanner {
  fun selectNext(manifest: DecompositionManifest): GoalRunnerSelection {
    val intended = manifest.currentSubtaskIntent.subtaskId
      .takeIf { it > 0 }
      ?.let { id -> manifest.subtasks.firstOrNull { it.id == id } }
    val candidate = intended?.takeUnless { it.status in setOf("complete", "skipped") }
      ?: manifest.subtasks.firstOrNull { it.status == "in_progress" }
      ?: manifest.subtasks.firstOrNull { it.status == "blocked" }
      ?: manifest.subtasks.firstOrNull { it.status == "pending" && dependenciesComplete(manifest, it) }
    return when {
      candidate == null -> {
        val blockedByDependency = manifest.subtasks.firstOrNull { it.status == "pending" }
        if (blockedByDependency == null) {
          GoalRunnerSelection.Done
        } else {
          GoalRunnerSelection.Blocked(
            subtask = blockedByDependency,
            reason = "Subtask ${blockedByDependency.id} is waiting for incomplete dependencies.",
          )
        }
      }
      candidate.status == "pending" && !dependenciesComplete(manifest, candidate) ->
        GoalRunnerSelection.Blocked(
          subtask = candidate,
          reason = "Subtask ${candidate.id} is waiting for incomplete dependencies.",
        )
      else -> GoalRunnerSelection.Run(
        GoalRunnerSubtaskDecision(
          subtask = candidate,
          action = if (candidate.status == "pending") GoalRunnerSubtaskAction.START else GoalRunnerSubtaskAction.RESUME,
        ),
      )
    }
  }

  private fun dependenciesComplete(manifest: DecompositionManifest, subtask: DecompositionSubtask): Boolean {
    val subtasksById = manifest.subtasks.associateBy(DecompositionSubtask::id)
    return subtask.dependencies.all { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId] ?: return@all false
      dependencySubtask.status == "complete" ||
        dependencySubtask.status == "skipped" ||
        dependency.optional && dependency.skipped
    }
  }
}

object GoalRunnerOutcomeReconciler {
  fun reconcile(
    subtaskId: Int,
    launchFacts: GoalRunnerLaunchFacts,
    storedOutcome: GoalRunnerStoredOutcome?,
  ): GoalRunnerReconciledOutcome = when {
    launchFacts.timedOut -> stop(
      reason = GoalRunnerStopReason.TIMEOUT,
      blockedReason = "Subtask $subtaskId timed out before reaching a terminal workflow-store outcome.",
      storedOutcome = storedOutcome,
    )
    launchFacts.spawnFailed -> stop(
      reason = GoalRunnerStopReason.BLOCKED,
      blockedReason = "Subtask $subtaskId could not start a fresh agent process.",
      storedOutcome = storedOutcome,
    )
    storedOutcome == null -> GoalRunnerReconciledOutcome.Stop(
      reason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
      blockedReason = "Subtask $subtaskId finished without a terminal workflow-store outcome.",
      workflowId = null,
      commitSha = null,
      lastResumableStep = "preplan",
    )
    !storedOutcome.suppressPr -> stop(
      reason = GoalRunnerStopReason.BLOCKED,
      blockedReason = "Subtask $subtaskId did not suppress per-subtask PR creation.",
      storedOutcome = storedOutcome,
    )
    else -> reconcileStoredOutcome(subtaskId, storedOutcome)
  }

  private fun reconcileStoredOutcome(
    subtaskId: Int,
    storedOutcome: GoalRunnerStoredOutcome,
  ): GoalRunnerReconciledOutcome = when (storedOutcome.status) {
    GoalRunnerTerminalStatus.COMPLETE -> completeOutcome(subtaskId, storedOutcome)
    GoalRunnerTerminalStatus.FAILED -> stop(
      reason = GoalRunnerStopReason.FAILED,
      blockedReason = storedOutcome.blockedReason.orEmpty().ifBlank { "Subtask $subtaskId failed." },
      storedOutcome = storedOutcome,
    )
    GoalRunnerTerminalStatus.BLOCKED -> stop(
      reason = GoalRunnerStopReason.BLOCKED,
      blockedReason = storedOutcome.blockedReason.orEmpty().ifBlank { "Subtask $subtaskId is blocked." },
      storedOutcome = storedOutcome,
    )
    GoalRunnerTerminalStatus.TIMEOUT -> stop(
      reason = GoalRunnerStopReason.TIMEOUT,
      blockedReason = storedOutcome.blockedReason.orEmpty()
        .ifBlank { "Subtask $subtaskId timed out before completion." },
      storedOutcome = storedOutcome,
    )
    GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME -> stop(
      reason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
      blockedReason = storedOutcome.blockedReason.orEmpty()
        .ifBlank { "Subtask $subtaskId has no terminal workflow-store outcome." },
      storedOutcome = storedOutcome,
    )
  }

  private fun completeOutcome(subtaskId: Int, storedOutcome: GoalRunnerStoredOutcome): GoalRunnerReconciledOutcome {
    val commitSha = storedOutcome.commitSha
    return if (commitSha.isNullOrBlank()) {
      stop(
        reason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
        blockedReason = "Subtask $subtaskId completed without commit_sha in the workflow store.",
        storedOutcome = storedOutcome,
      )
    } else {
      GoalRunnerReconciledOutcome.Complete(
        workflowId = storedOutcome.workflowId,
        commitSha = commitSha,
        lastResumableStep = storedOutcome.lastResumableStep.orEmpty().ifBlank { "commit_push" },
      )
    }
  }

  private fun stop(
    reason: GoalRunnerStopReason,
    blockedReason: String,
    storedOutcome: GoalRunnerStoredOutcome?,
  ): GoalRunnerReconciledOutcome.Stop = GoalRunnerReconciledOutcome.Stop(
    reason = reason,
    blockedReason = blockedReason,
    workflowId = storedOutcome?.workflowId,
    commitSha = storedOutcome?.commitSha,
    lastResumableStep = storedOutcome?.lastResumableStep.orEmpty().ifBlank { "preplan" },
  )
}
