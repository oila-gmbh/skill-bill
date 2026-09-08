package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

private data class NormalizationPreparation(
  val request: NormalizedSubtaskCommitRequest,
  val originalIdentities: List<FeatureTaskRuntimeCheckpointIdentity>,
  val indexPaths: List<String>,
  val indexSnapshot: String,
  val firstParent: String,
  val ownedDirtyPaths: List<String>,
)

internal sealed interface NormalizationPreparationOutcome

private data class NormalizationReady(
  val value: NormalizationPreparation,
) : NormalizationPreparationOutcome

internal data class NormalizationRefused(val reason: String) : NormalizationPreparationOutcome

internal fun writeNormalizedSubtaskCommit(request: NormalizedSubtaskCommitRequest): Boolean {
  return when (val preparation = prepareNormalization(request)) {
    is NormalizationRefused -> refuseSubtaskMigration(request.refusal(preparation.reason))
    is NormalizationReady -> writePreparedNormalization(preparation.value)
  }
}

private fun writePreparedNormalization(preparation: NormalizationPreparation): Boolean {
  val request = preparation.request
  val preserved = preserveSubtaskMigrationCommits(
    request.runLoop,
    request.active,
    (request.identities.maxOfOrNull { it.sequenceNumber } ?: -1) + 2,
  )
  if (preserved != null) return abortNormalization(preparation, preserved)
  val reset = request.runLoop.phaseGates.gitOperations.resetSoftToCommit(
    request.runLoop.request.repoRoot,
    preparation.firstParent,
  )
  if (!reset.ok) {
    return abortNormalization(
      preparation,
      "active subtask span normalization could not reset to '${preparation.firstParent}' " +
        "(${reset.error}); operator decision: restore the branch before resuming",
    )
  }
  val created = createNormalizedCommit(preparation)
  if (!created.ok || created.value.orEmpty().isBlank()) {
    return abortNormalization(
      preparation,
      "active subtask span normalization could not create its replacement commit " +
        "(${created.error}); operator decision: inspect the preserved checkpoint refs before retrying",
    )
  }
  return settleNormalizedCommit(preparation, created.value.orEmpty().trim())
}

private fun prepareNormalization(request: NormalizedSubtaskCommitRequest): NormalizationPreparationOutcome {
  val result = request.runLoop.phaseGates.gitOperations.dirtyImplementationPaths(
    request.runLoop.request.repoRoot,
  )
  return when (result) {
    is DirtyPathsError -> NormalizationRefused(result.reason)
    is DirtyPaths -> prepareNormalizationFromDirty(request, result.paths)
  }
}

internal fun prepareNormalizationAfterStaged(
  request: NormalizedSubtaskCommitRequest,
  dirtyPaths: List<String>,
  stagedPaths: List<String>,
): NormalizationPreparationOutcome {
  val indexPaths = (stagedPaths + request.ownedPaths).distinct()
  val snapshot = captureNormalizationIndex(request, indexPaths)
  if (!snapshot.ok) {
    return refusedNormalization(
      "the pre-normalization index could not be captured (${snapshot.error}); " +
        "operator decision: preserve the index before retrying",
    )
  }
  val stagedOwned = request.runLoop.phaseGates.gitOperations.stagePaths(
    request.runLoop.request.repoRoot,
    request.ownedPaths,
  )
  if (!stagedOwned.ok) {
    restoreNormalizationIndex(request, indexPaths, snapshot.value.orEmpty())
    return refusedNormalization(
      "owned repair content could not be staged for normalization (${stagedOwned.error}); " +
        "operator decision: preserve the index before retrying",
    )
  }
  val firstParent = request.active.first().parentSha?.trim().takeIf { !it.isNullOrBlank() }
    ?: run {
      restoreNormalizationIndex(request, indexPaths, snapshot.value.orEmpty())
      return refusedNormalization(
        "the first active commit has no durable parent SHA; operator decision: identify the span base " +
          "before normalizing accumulated commits",
      )
    }
  return NormalizationReady(
    NormalizationPreparation(
      request,
      request.identities.toList(),
      indexPaths,
      snapshot.value.orEmpty(),
      firstParent,
      dirtyPaths.filter { it in request.ownedPaths },
    ),
  )
}

private fun createNormalizedCommit(preparation: NormalizationPreparation): WorkflowGitOperationResult =
  preparation.request.runLoop.phaseGates.gitOperations.writeSubtaskCommitPreservingHistory(
    SubtaskCommitPreservationRequest(
      repoRoot = preparation.request.runLoop.request.repoRoot,
      decision = FeatureTaskRuntimeSubtaskCommitCreate,
      identity = FeatureTaskRuntimeSubtaskCommitIdentity(
        preparation.request.active.first().issueKey,
        preparation.request.active.first().subtaskId,
      ),
      message = preparation.request.message,
      allowUnchangedIndex = false,
      ownedPaths = preparation.request.ownedPaths,
      record = { preparation.request.runLoop.diagnostics.warning(it) },
    ),
  )

private fun abortNormalization(preparation: NormalizationPreparation, reason: String): Boolean {
  rollbackSubtaskMigration(
    preparation.request.runLoop,
    SubtaskMigrationRollbackRequest(
      headSha = preparation.request.headSha,
      stagedPaths = preparation.indexPaths,
      snapshot = preparation.indexSnapshot,
    ),
  )
  return refuseSubtaskMigration(preparation.request.refusal(reason))
}

private fun settleNormalizedCommit(preparation: NormalizationPreparation, replacementSha: String): Boolean {
  val treeFailure = replacementTreeFailure(preparation, replacementSha)
  if (treeFailure != null) return abortNormalization(preparation, treeFailure)
  val restored = restoreNormalizationIndex(
    preparation.request,
    preparation.indexPaths,
    preparation.indexSnapshot,
  )
  if (!restored) {
    return abortNormalization(
      preparation,
      "replacement commit '$replacementSha' was created but the pre-normalization index could not be restored; " +
        "operator decision: inspect git status before resuming",
    )
  }
  val recorded = recordNormalizedIdentity(preparation, replacementSha)
  if (!recorded) {
    return abortNormalization(preparation, "replacement commit '$replacementSha' has no durable identity")
  }
  return replaceNormalizedIdentities(preparation, replacementSha)
}

private fun replacementTreeFailure(preparation: NormalizationPreparation, replacementSha: String): String? {
  val git = preparation.request.runLoop.phaseGates.gitOperations
  val replacementTree = git.resolveCommit(
    preparation.request.runLoop.request.repoRoot,
    "$replacementSha^{tree}",
  )
  val originalTree = git.resolveCommit(
    preparation.request.runLoop.request.repoRoot,
    "${preparation.request.headSha}^{tree}",
  )
  val unresolvedTree = !replacementTree.ok || !originalTree.ok
  val changedUntouchedTree = preparation.ownedDirtyPaths.isEmpty() &&
    replacementTree.value.orEmpty().trim() != originalTree.value.orEmpty().trim()
  return if (unresolvedTree || changedUntouchedTree) {
    "replacement commit '$replacementSha' does not preserve the pre-normalization tree; " +
      "operator decision: restore from the preserved checkpoint refs"
  } else {
    null
  }
}

private fun recordNormalizedIdentity(preparation: NormalizationPreparation, replacementSha: String): Boolean {
  val request = preparation.request
  return request.runLoop.collaborators.checkpointContinued5.recordCheckpointIdentity(
    request.runLoop,
    RecordCheckpointIdentityArgs(
      precedingPhaseId = request.precedingPhaseId,
      branch = request.branch,
      loopId = null,
      ownedPaths = request.ownedPaths,
      parentSha = preparation.firstParent,
      commitSha = replacementSha,
      blockedReason = request.blockedReason,
    ),
  )
}

private fun replaceNormalizedIdentities(preparation: NormalizationPreparation, replacementSha: String): Boolean {
  val request = preparation.request
  val updated = runCatching {
    request.runLoop.recorder.loadCheckpointIdentities(
      request.runLoop.request.workflowId,
      request.runLoop.request.dbPathOverride,
    )
  }.getOrElse { error ->
    return refuseSubtaskMigration(
      request.refusal(
        "replacement checkpoint identities could not be read " +
          "(${error.message ?: error::class.simpleName}); operator decision: repair the workflow store before resuming",
        error,
      ),
    )
  }.orEmpty()
  val replacementIdentity = updated.lastOrNull { it.commitSha == replacementSha }
    ?: return refuseSubtaskMigration(
      request.refusal(
        "replacement commit '$replacementSha' has no durable identity; operator decision: " +
          "repair the workflow store before resuming",
      ),
    )
  val ref = updateNormalizedIdentityRef(request, replacementIdentity.checkpointRef, replacementSha)
  if (ref != null) return refuseSubtaskMigration(request.refusal(ref))
  val retained = updated
    .filterNot { candidate ->
      request.active.any { it.sequenceNumber == candidate.sequenceNumber }
    }
    .plus(replacementIdentity)
    .distinctBy(FeatureTaskRuntimeCheckpointIdentity::sequenceNumber)
    .sortedBy(FeatureTaskRuntimeCheckpointIdentity::sequenceNumber)
  return runCatching {
    request.runLoop.recorder.replaceCheckpointIdentities(
      request.runLoop.request.workflowId,
      retained,
      request.runLoop.request.dbPathOverride,
    )
  }.getOrElse { error ->
    refuseSubtaskMigration(
      request.refusal(
        "superseded active checkpoint identities could not be persisted " +
          "(${error.message ?: error::class.simpleName}); operator decision: repair the workflow store before resuming",
        error,
      ),
    )
  }
}
