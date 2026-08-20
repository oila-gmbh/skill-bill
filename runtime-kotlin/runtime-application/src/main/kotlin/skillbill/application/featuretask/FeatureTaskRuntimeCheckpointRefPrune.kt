package skillbill.application.featuretask

import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.deleteCheckpointRef
import skillbill.ports.workflow.listCheckpointRefs
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import java.nio.file.Path

private const val REF_LISTING_DELIMITER: Char = '\u0000'

internal data class FeatureTaskRuntimeCheckpointRefPruneRequest(
  val issueKey: String,
  val subtaskId: String,
  val manifestCommitSha: String?,
  val bypassEligibilityGate: Boolean = false,
  val featureBranch: String? = null,
)

internal fun subtaskCommitReachableOnRemote(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  featureBranch: String,
  commitSha: String,
): Boolean {
  val branch = featureBranch.trim()
  val sha = commitSha.trim()
  if (branch.isBlank() || sha.isBlank()) return false
  val remoteTip = gitOperations.resolveCommit(repoRoot, "origin/$branch")
  if (!remoteTip.ok) return false
  val remoteSha = remoteTip.value.orEmpty().trim().takeIf(String::isNotBlank) ?: return false
  val reachable = gitOperations.isCommitAncestor(repoRoot, sha, remoteSha)
  return reachable.ok && reachable.value.orEmpty().trim().equals("true", ignoreCase = true)
}

internal data class FeatureTaskRuntimeCheckpointRefPruneResult(
  val attempted: Boolean,
  val deletedRefCount: Int,
  val skippedReason: String? = null,
)

internal fun featureTaskRuntimeSubtaskCheckpointRefPrefix(issueKey: String, subtaskId: String): String =
  "${FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE}/${issueKey.trim()}/$subtaskId/"

internal fun WorkflowGitOperations.pruneSubtaskCheckpointRefs(
  repoRoot: Path,
  request: FeatureTaskRuntimeCheckpointRefPruneRequest,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult {
  val issueKey = request.issueKey.trim()
  val subtaskId = request.subtaskId.trim()
  if (issueKey.isBlank() || subtaskId.isBlank()) {
    return FeatureTaskRuntimeCheckpointRefPruneResult(
      attempted = false,
      deletedRefCount = 0,
      skippedReason = "issue key and subtask id are required to prune checkpoint refs",
    )
  }
  if (!request.bypassEligibilityGate) {
    pruneEligibilityResult(repoRoot, request, record)?.let { return it }
  }
  return pruneListedCheckpointRefs(repoRoot, issueKey, subtaskId, record)
}

private fun WorkflowGitOperations.pruneEligibilityResult(
  repoRoot: Path,
  request: FeatureTaskRuntimeCheckpointRefPruneRequest,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult? {
  val manifestSha = request.manifestCommitSha?.trim().orEmpty()
  if (manifestSha.isBlank()) {
    record(
      "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
        "value_used='blank manifest commit_sha for ${request.issueKey}/${request.subtaskId}' " +
        "value_expected=a recorded post-push commit sha before checkpoint ref pruning " +
        "cause=pruning deferred until the goal runner records commit_sha after push",
    )
    return FeatureTaskRuntimeCheckpointRefPruneResult(
      attempted = false,
      deletedRefCount = 0,
      skippedReason = "manifest commit_sha is blank; checkpoint refs stay until the subtask commit is recorded",
    )
  }
  val featureBranch = request.featureBranch?.trim().orEmpty()
  if (featureBranch.isNotBlank() &&
    !subtaskCommitReachableOnRemote(this, repoRoot, featureBranch, manifestSha)
  ) {
    record(
      "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
        "value_used='commit $manifestSha not reachable from origin/$featureBranch' " +
        "value_expected=the subtask commit on the published remote tip before pruning " +
        "cause=pruning deferred until push evidence exists for ${request.issueKey}/${request.subtaskId}",
    )
    return FeatureTaskRuntimeCheckpointRefPruneResult(
      attempted = false,
      deletedRefCount = 0,
      skippedReason =
      "subtask commit is not reachable on origin/$featureBranch; checkpoint refs stay until push is verified",
    )
  }
  return null
}

private fun WorkflowGitOperations.pruneListedCheckpointRefs(
  repoRoot: Path,
  issueKey: String,
  subtaskId: String,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult {
  val prefix = featureTaskRuntimeSubtaskCheckpointRefPrefix(issueKey, subtaskId)
  val listed = listCheckpointRefs(repoRoot, prefix)
  if (!listed.ok) {
    record(
      "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
        "value_used='ref listing failed for $prefix' " +
        "value_expected=NUL-delimited refs under the subtask namespace " +
        "cause=${listed.error}",
    )
    return FeatureTaskRuntimeCheckpointRefPruneResult(
      attempted = true,
      deletedRefCount = 0,
      skippedReason = listed.error,
    )
  }
  val refs = parseCheckpointRefListing(listed.value.orEmpty())
  if (refs.isEmpty()) {
    return FeatureTaskRuntimeCheckpointRefPruneResult(attempted = true, deletedRefCount = 0)
  }
  return deleteListedCheckpointRefs(repoRoot, prefix, refs, record)
}

private fun WorkflowGitOperations.deleteListedCheckpointRefs(
  repoRoot: Path,
  prefix: String,
  refs: List<String>,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult {
  var deleted = 0
  refs.forEach { refName ->
    val removed = deleteCheckpointRef(repoRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, refName)
    if (!removed.ok) {
      record(
        "seam=FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs " +
          "value_used='delete failed for $refName' " +
          "value_expected=an absent or deleted ref under $prefix " +
          "cause=${removed.error}",
      )
      return@forEach
    }
    deleted++
  }
  return FeatureTaskRuntimeCheckpointRefPruneResult(attempted = true, deletedRefCount = deleted)
}

internal fun parseCheckpointRefListing(raw: String): List<String> = raw.split(REF_LISTING_DELIMITER)
  .filter(String::isNotBlank)
  .chunked(2)
  .mapNotNull { parts ->
    parts.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
  }
  .distinct()
  .sorted()

internal fun pruneCompletedSubtaskCheckpointRefs(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  request: FeatureTaskRuntimeCheckpointRefPruneRequest,
  record: (String) -> Unit,
): FeatureTaskRuntimeCheckpointRefPruneResult = gitOperations.pruneSubtaskCheckpointRefs(repoRoot, request, record)

internal fun pruneResetSubtaskCheckpointRefs(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  issueKey: String,
  subtaskIds: Collection<Int>,
  record: (String) -> Unit,
): Int = subtaskIds.sumOf { subtaskId ->
  gitOperations.pruneSubtaskCheckpointRefs(
    repoRoot = repoRoot,
    request = FeatureTaskRuntimeCheckpointRefPruneRequest(
      issueKey = issueKey,
      subtaskId = subtaskId.toString(),
      manifestCommitSha = null,
      bypassEligibilityGate = true,
    ),
    record = record,
  ).deletedRefCount
}
