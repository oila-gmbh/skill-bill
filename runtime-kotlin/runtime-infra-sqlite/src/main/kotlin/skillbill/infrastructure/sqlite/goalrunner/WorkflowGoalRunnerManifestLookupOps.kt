package skillbill.infrastructure.sqlite.goalrunner

import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.IssueKey
import java.nio.file.Path

internal class WorkflowGoalRunnerManifestLookupOps(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
  private val save: (GoalRunnerManifestState) -> GoalRunnerManifestState,
) : GoalRunnerManifestLookup {

  override fun loadByIssueKey(issueKey: IssueKey, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> ctx.manifestLoader.findProjectedManifest(root, issueKey.value) }
    val stored = ctx.manifestLoader.loadFromWorkflowStore(issueKey.value, projected)
    if (ctx.manifestLoader.shouldRefreshFromCompleteProjection(stored, projected)) {
      return save(
        requireNotNull(stored).copy(manifest = requireNotNull(projected), repoRoot = repoRoot),
      )
    }
    return stored?.copy(repoRoot = repoRoot) ?: projected?.let { manifest ->
      ctx.manifestLoader.importFromManifestProjection(manifest)?.copy(repoRoot = repoRoot)
    }
  }
  override fun readByIssueKey(issueKey: IssueKey, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> ctx.manifestLoader.findProjectedManifest(root, issueKey.value) }
    val stored = ctx.manifestLoader.loadFromWorkflowStore(issueKey.value, projected)
    return ctx.manifestLoader.readProjection(stored, projected, repoRoot)
  }
  override fun readByIssueKeyIfPresent(issueKey: IssueKey, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root ->
      ctx.manifestLoader.findProjectedManifest(root, issueKey.value, recoverPending = false)
    }
    val stored = ctx.manifestLoader.loadFromWorkflowStoreIfPresent(issueKey.value, projected)
    return ctx.manifestLoader.readProjection(stored, projected, repoRoot)
  }
  override fun loadDurableByIssueKey(issueKey: IssueKey): GoalRunnerManifestState? =
    ctx.manifestLoader.loadFromWorkflowStore(issueKey.value, currentProjectedManifest = null)
}
