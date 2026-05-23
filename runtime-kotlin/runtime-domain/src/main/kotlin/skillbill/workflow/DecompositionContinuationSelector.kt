package skillbill.workflow

import skillbill.workflow.model.DecompositionBranchPlan
import skillbill.workflow.model.DecompositionContinuationSelection
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

object DecompositionContinuationSelector {
  fun select(manifest: DecompositionManifest): DecompositionContinuationSelection {
    val inProgress = manifest.subtasks.firstOrNull { it.status == "in_progress" }
    val firstPending = manifest.subtasks.firstOrNull { it.status == "pending" && dependenciesComplete(manifest, it) }
    val blocked = manifest.subtasks.firstOrNull { it.status == "blocked" }
    return when {
      inProgress != null -> DecompositionContinuationSelection.Resume(
        subtask = inProgress,
        workflowId = inProgress.workflowId.orEmpty(),
        resumeStepId = inProgress.lastResumableStep.orEmpty(),
      )
      firstPending != null -> DecompositionContinuationSelection.Start(
        subtask = firstPending,
        branchPlan = manifest.branchPlanFor(firstPending.id),
      )
      blocked != null -> DecompositionContinuationSelection.Blocked(
        subtask = blocked,
        reason = blocked.blockedReason?.takeIf(String::isNotBlank)
          ?: "Subtask ${blocked.id} is blocked.",
      )
      else -> DecompositionContinuationSelection.Done(manifest = manifest)
    }
  }

  private fun dependenciesComplete(manifest: DecompositionManifest, subtask: DecompositionSubtask): Boolean {
    val subtasksById = manifest.subtasks.associateBy(DecompositionSubtask::id)
    return subtask.dependencies.all { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId] ?: return@all false
      dependencySubtask.status == "complete" ||
        dependencySubtask.status == "skipped" ||
        dependency.isExplicitlySkipped()
    }
  }

  private fun DecompositionDependency.isExplicitlySkipped(): Boolean = optional && skipped

  private fun DecompositionManifest.branchPlanFor(subtaskId: Int): DecompositionBranchPlan = when (executionModel) {
    DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK ->
      DecompositionBranchPlan(branch = featureBranch.orEmpty(), baseBranch = baseBranch, validateBase = false)
    DecompositionExecutionModel.STACKED_BRANCHES -> {
      val stackBranch = stackBranches.first { it.subtaskId == subtaskId }
      DecompositionBranchPlan(branch = stackBranch.branch, baseBranch = stackBranch.baseBranch, validateBase = true)
    }
  }
}
