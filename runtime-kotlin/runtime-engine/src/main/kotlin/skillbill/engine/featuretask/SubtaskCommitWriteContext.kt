package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

internal fun reconciliationFailureResult(
  runLoop: FeatureTaskRuntimeRunLoop,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  reason: String,
  cause: Throwable?,
): WorkflowGitOperationResult {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = identity.issueKey,
    subtaskId = identity.subtaskId,
    reason = "$reason; operator decision: repair durable branch ownership before retrying",
    cause = cause,
  )
  runCatching {
    runLoop.diagnostics.warning(
      "record_kind=refusal seam=FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger.writeSubtaskCommit " +
        "value_used='${identity.issueKey}/${identity.subtaskId}' value_expected=durable branch ownership " +
        "cause=${error.reason}",
      error,
    )
  }
  return WorkflowGitOperationResult(status = "error", error = error.message.orEmpty())
}

internal data class SubtaskCommitWriteContext(
  val ledger: SubtaskCommitLedgerState? = null,
  val headSha: String? = null,
  val headOk: Boolean = false,
  val headError: String? = null,
  val unpushed: Boolean = false,
  val unpushedOk: Boolean = false,
  val unpushedError: String? = null,
  val headMessage: String = "",
  val currentBranch: Pair<String, Pair<Boolean, String?>> = "" to (false to null),
  val resolvedBranch: FeatureTaskRuntimeResolvedBranch? = null,
  val failure: String? = null,
)

internal fun loadSubtaskCommitWriteContext(
  runLoop: FeatureTaskRuntimeRunLoop,
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): SubtaskCommitWriteContext {
  val ledger = try {
    runLoop.collaborators.checkpointContinued4.subtaskCommitLedgerState(runLoop, identity)
  } catch (error: FeatureTaskRuntimeSubtaskCommitReconciliationError) {
    return SubtaskCommitWriteContext(failure = error.message.orEmpty())
  }
  val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
  val unpushed = runLoop.phaseGates.gitOperations.localBranchHasUnpushedCommits(
    runLoop.request.repoRoot,
    branch,
  )
  val resolvedBranch = runCatching {
    runLoop.recorder.loadResolvedBranch(runLoop.request.workflowId, runLoop.request.dbPathOverride)
  }.getOrElse { error ->
    return SubtaskCommitWriteContext(
      failure = "durable resolved-branch ownership could not be read (${error.message})",
    )
  }
  return SubtaskCommitWriteContext(
    ledger = ledger,
    headSha = head.value.trim().takeIf { head.ok && it.isNotBlank() },
    headOk = head.ok,
    headError = head.error,
    unpushed = unpushed.value.trim().equals("true", ignoreCase = true),
    unpushedOk = unpushed.ok,
    unpushedError = unpushed.error,
    headMessage = headCommitMessageOrNull(runLoop).orEmpty(),
    currentBranch = runLoop.phaseGates.gitOperations.currentBranch(runLoop.request.repoRoot).let { result ->
      result.value.trim() to (result.ok to result.error)
    },
    resolvedBranch = resolvedBranch,
  )
}

internal fun SubtaskCommitWriteContext.ownershipFailure(
  isGoalContinuation: Boolean,
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): String? = basicOwnershipFailure(branch)
  ?: durableOwnershipFailure(branch, identity)
  ?: unownedOwnershipFailure(isGoalContinuation, identity)

private fun SubtaskCommitWriteContext.basicOwnershipFailure(branch: String): String? = when {
  !headOk -> "HEAD could not be resolved ($headError)"
  !unpushedOk -> "whether the branch has unpushed commits could not be resolved ($unpushedError)"
  !currentBranch.second.first || currentBranch.first != branch.trim() ->
    "checked-out branch '${currentBranch.first}' does not match the resolved subtask branch '$branch'"
  else -> null
}

private fun SubtaskCommitWriteContext.durableOwnershipFailure(
  branch: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): String? = when {
  ledger?.branch != null && ledger.branch.trim() != branch.trim() ->
    "durable subtask identity belongs to branch '${ledger.branch}', not '$branch'"
  ledger?.commitSha != null && headSha != ledger.commitSha ->
    "durable subtask identity names '${ledger.commitSha}', but HEAD is '${headSha ?: "unresolved"}'"
  ledger?.commitSha != null && !identity.matches(headMessage) ->
    "durable subtask HEAD '${headSha.orEmpty()}' does not carry the matching '${identity.trailer}' trailer"
  else -> null
}

private fun SubtaskCommitWriteContext.unownedOwnershipFailure(
  isGoalContinuation: Boolean,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): String? {
  val noDurableCommit = ledger?.commitSha == null
  val hasHead = headSha != null
  val identityMatches = identity.matches(headMessage)
  if (!noDurableCommit || !hasHead || identityMatches) return null
  return when {
    isGoalContinuation && resolvedBranch?.branch == currentBranch.first &&
      resolvedBranch.reviewBaseSha == headSha -> null
    unpushed ->
      "checked-out HEAD '$headSha' is an unowned unpushed commit and has no matching " +
        "'${identity.trailer}' trailer"
    headMessage.contains("Skill-Bill-Subtask:") -> "checked-out HEAD '$headSha' carries another subtask trailer"
    isGoalContinuation && resolvedBranch?.reviewBaseSha != headSha ->
      "checked-out HEAD '$headSha' is not the durable subtask base " +
        "'${resolvedBranch?.reviewBaseSha ?: "unresolved"}' " +
        "and has no matching '${identity.trailer}' trailer"
    else -> null
  }
}

private fun headCommitMessageOrNull(runLoop: FeatureTaskRuntimeRunLoop): String? =
  runLoop.phaseGates.gitOperations.headCommitMessage(runLoop.request.repoRoot).takeIf { it.ok }?.value
