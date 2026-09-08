package skillbill.infrastructure.sqlite.goalrunner

import skillbill.ports.goalrunner.runner.GoalRunnerManifestLookup
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import java.nio.file.Path

internal class WorkflowGoalRunnerManifestLookupOps(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
  private val save: (GoalRunnerManifestState, String?) -> GoalRunnerManifestState,
) : GoalRunnerManifestLookup {

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> ctx.manifestLoader.findProjectedManifest(root, issueKey) }
    val stored = ctx.manifestLoader.loadFromWorkflowStore(issueKey, dbPathOverride, projected)
    if (ctx.manifestLoader.shouldRefreshFromCompleteProjection(stored, projected)) {
      return save(
        requireNotNull(stored).copy(manifest = requireNotNull(projected), repoRoot = repoRoot),
        dbPathOverride,
      )
    }
    return stored?.copy(repoRoot = repoRoot) ?: projected?.let { manifest ->
      ctx.manifestLoader.importFromManifestProjection(manifest, dbPathOverride)?.copy(repoRoot = repoRoot)
    }
  }
  override fun readByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> ctx.manifestLoader.findProjectedManifest(root, issueKey) }
    val stored = ctx.manifestLoader.loadFromWorkflowStore(issueKey, dbPathOverride, projected)
    return ctx.manifestLoader.readProjection(stored, projected, repoRoot)
  }
  override fun readByIssueKeyIfPresent(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root ->
      ctx.manifestLoader.findProjectedManifest(root, issueKey, recoverPending = false)
    }
    val stored = ctx.manifestLoader.loadFromWorkflowStoreIfPresent(issueKey, dbPathOverride, projected)
    return ctx.manifestLoader.readProjection(stored, projected, repoRoot)
  }
  override fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String?): GoalRunnerManifestState? =
    ctx.manifestLoader.loadFromWorkflowStore(issueKey, dbPathOverride, currentProjectedManifest = null)
}
