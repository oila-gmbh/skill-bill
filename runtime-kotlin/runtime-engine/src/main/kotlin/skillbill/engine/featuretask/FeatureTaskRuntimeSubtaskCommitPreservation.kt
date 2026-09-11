package skillbill.engine.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.amendHeadCommit
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.ports.workflow.gitops.unstagePaths
import skillbill.ports.workflow.gitops.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal fun WorkflowGitOperations.writeSubtaskCommitPreservingHistory(
  request: SubtaskCommitPreservationRequest,
): WorkflowGitOperationResult {
  val staged = stagedPaths(request.repoRoot)
  if (!staged.ok) return staged
  val owned = request.ownedPaths.map(::normalizeRepoPath).toSet()
  val foreign = staged.value.orEmpty().split('\u0000')
    .map(::normalizeRepoPath)
    .filter(String::isNotBlank)
    .filterNot { it in owned }
    .distinct()
  val foreignSnapshot = captureIndexState(request.repoRoot, foreign)
  if (!foreignSnapshot.ok) return foreignSnapshot
  var restored = WorkflowGitOperationResult(status = "ok")
  val committed = try {
    val unstaged = unstagePaths(request.repoRoot, foreign)
    if (!unstaged.ok) {
      unstaged
    } else if (request.decision !is FeatureTaskRuntimeSubtaskCommitAmend) {
      createCommit(request.repoRoot, request.message)
    } else {
      amendSubtaskCommitPreservingHistory(this, request, request.decision)
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: IllegalStateException) {
    WorkflowGitOperationResult(status = "error", error = "subtask commit write failed (${error.message})")
  } finally {
    restored = restoreForeignIndex(request.repoRoot, foreign, foreignSnapshot.value.orEmpty())
    if (!restored.ok) {
      request.record("record_kind=refusal seam=writeSubtaskCommitPreservingHistory cause=${restored.error}")
    }
  }
  if (!restored.ok) {
    request.record(
      "record_kind=refusal seam=writeSubtaskCommitPreservingHistory value_used='foreign staged index' " +
        "value_expected=restored foreign staged content cause=${restored.error}",
    )
    return WorkflowGitOperationResult(
      status = "error",
      error = "subtask commit write could not restore foreign staged content (${restored.error})",
    )
  }
  return committed
}

private fun WorkflowGitOperations.restoreForeignIndex(
  repoRoot: Path,
  paths: List<String>,
  snapshot: String,
): WorkflowGitOperationResult = try {
  if (paths.isEmpty()) WorkflowGitOperationResult(status = "ok") else restoreIndexState(repoRoot, paths, snapshot)
} catch (error: CancellationException) {
  throw error
} catch (error: IllegalStateException) {
  WorkflowGitOperationResult(
    status = "error",
    error = "foreign staged index restoration failed (${error.message})",
  )
}

private fun amendSubtaskCommitPreservingHistory(
  gitOperations: WorkflowGitOperations,
  request: SubtaskCommitPreservationRequest,
  decision: FeatureTaskRuntimeSubtaskCommitAmend,
): WorkflowGitOperationResult {
  if (decision.recoveredFromTrailer) {
    request.record(
      FeatureTaskRuntimeSubtaskCommitResolver.trailerFallbackRecord(
        request.identity,
        decision.ownedHeadSha,
      ),
    )
  }
  if (decision.rewritesPublishedHistory) {
    request.record(
      FeatureTaskRuntimeSubtaskCommitResolver.publishedHistoryRewriteRecord(request.identity, decision.ownedHeadSha),
    )
  }
  val refName = request.identity.checkpointRefName(decision.sequenceNumber)
  val preservation = preservePreAmendCheckpoint(gitOperations, request, refName, decision.ownedHeadSha)
  if (preservation != null) return preservation
  return gitOperations.amendHeadCommit(
    request.repoRoot,
    decision.ownedHeadSha,
    request.message,
    request.allowUnchangedIndex,
  )
}

private fun preservePreAmendCheckpoint(
  gitOperations: WorkflowGitOperations,
  request: SubtaskCommitPreservationRequest,
  refName: String,
  ownedHeadSha: String,
): WorkflowGitOperationResult? {
  val existing = gitOperations.resolveCheckpointRef(
    request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
  )
  if (!existing.ok) {
    return preAmendPreservationFailure(
      refName,
      "whether that ref already preserves another commit could not be determined (${existing.error})",
    )
  }
  val occupant = existing.value.orEmpty().trim()
  if (occupant.isNotBlank() && occupant != ownedHeadSha) {
    return preAmendPreservationFailure(
      refName,
      "that ref already preserves '$occupant'; refusing to overwrite recovery history",
    )
  }
  val written = gitOperations.updateCheckpointRef(
    request.repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    refName,
    ownedHeadSha,
  )
  if (!written.ok) return preAmendPreservationFailure(refName, written.error)
  return verifyPreservedCheckpoint(gitOperations, request.repoRoot, refName, ownedHeadSha)
}

private fun verifyPreservedCheckpoint(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  refName: String,
  ownedHeadSha: String,
): WorkflowGitOperationResult? {
  val resolved = gitOperations.resolveCheckpointRef(repoRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, refName)
  val preserved = resolved.value.orEmpty().trim()
  if (!resolved.ok || preserved != ownedHeadSha) {
    return preAmendPreservationFailure(
      refName,
      resolved.error.takeIf { it.isNotBlank() }
        ?: "the ref resolved to '$preserved' rather than the pre-amend commit '$ownedHeadSha'",
    )
  }
  return null
}

private fun preAmendPreservationFailure(refName: String, error: String) = WorkflowGitOperationResult(
  status = "error",
  error = "the pre-amend checkpoint commit could not be preserved at '$refName' ($error); the amend " +
    "did not run and HEAD is unchanged",
)
