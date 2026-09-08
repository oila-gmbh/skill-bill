package skillbill.ports.workflow.gitops

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import java.nio.file.Path

internal object NoopWorkflowGitWorktreeOperations : WorkflowGitWorktreeOperations {
  private const val NAME = "NoopWorkflowGitWorktreeOperations"

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "worktreeStatus(repoRoot=$repoRoot)")
    return WorkflowGitOperationResult(status = "ok", value = "")
  }

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "worktreeActivity(repoRoot=$repoRoot)")
    return WorkflowWorktreeActivityResult(
      status = "ok",
      changedFileSummary = GoalObservabilityChangedFileSummary(
        total = 0,
        added = 0,
        modified = 0,
        deleted = 0,
        renamed = 0,
        untracked = 0,
      ),
      diffStat = GoalObservabilityDiffStat(filesChanged = 0, insertions = 0, deletions = 0),
    )
  }

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "selectedDiffHunks(repoRoot=$repoRoot, paths=${request.paths})")
    return WorkflowSelectedDiffHunksResult(
      status = "ok",
      selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
    )
  }
}
