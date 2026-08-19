package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.amendHeadCommit
import skillbill.ports.workflow.captureIndexState
import skillbill.ports.workflow.headCommitMessage
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.resolveCheckpointRef
import skillbill.ports.workflow.restoreIndexState
import skillbill.ports.workflow.stagePaths
import skillbill.ports.workflow.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import java.nio.file.Path

private const val COMMIT_PUSH_RESULT_KEY = "commit_push_result"
private const val OUTCOME_MESSAGE_KEY = "message"
private const val CHANGED_PATHS_KEY = "changed_paths"
private const val COMMIT_SHA_KEY = "commit_sha"
private const val GOVERNED_SPEC_ROOT = ".feature-specs/"

/** The agent's half of the finalisation contract: what to say, and which paths the runtime may stage. */
internal data class FeatureTaskRuntimeCommitPushHandoff(
  val outcomeMessage: String,
  val changedPaths: List<String>,
)

internal sealed interface FeatureTaskRuntimeCommitPushHandoffResult {
  data class Valid(val handoff: FeatureTaskRuntimeCommitPushHandoff) : FeatureTaskRuntimeCommitPushHandoffResult

  data class Invalid(val reason: String) : FeatureTaskRuntimeCommitPushHandoffResult
}

internal sealed interface FeatureTaskRuntimeSubtaskFinalisationResult {
  data class Finalised(
    val commitSha: String,
    val stagedPaths: List<String>,
    val excludedSpecPaths: List<String>,
    val forcedWithLease: Boolean,
  ) : FeatureTaskRuntimeSubtaskFinalisationResult

  data class Blocked(val reason: String) : FeatureTaskRuntimeSubtaskFinalisationResult
}

/**
 * SKILL-190: the runtime half of `commit_push`.
 *
 * The agent describes the outcome and enumerates what it touched; every git write below is the
 * runtime's. Order is load-bearing: the handoff is validated before anything is staged, so a rejected
 * finalisation leaves the repository exactly as the agent left it; the message is applied in the same
 * amend that stages the content, so no commit ever reaches a pushed state carrying the provisional
 * subject; and the sha is captured after that amend, so the value threaded into the manifest is the
 * final one rather than an intermediate.
 *
 * [recordCommit] persists the durable pointer to the commit just written and runs BEFORE the push,
 * returning a blocking reason when it cannot. Recording after the push left a failed push blocked with
 * HEAD at the finalisation commit while the pointer still named the pre-amend checkpoint sha, so the
 * re-entry resolved Create against a clean index and the subtask could never finish.
 */
internal class FeatureTaskRuntimeSubtaskFinalisation(
  private val gitOperations: WorkflowGitOperations,
  private val repoRoot: Path,
  private val record: (String) -> Unit,
) {
  @Suppress("ReturnCount") // each early return is one failure the caller must see as a distinct block
  fun finalise(
    branch: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    durableCommitSha: String?,
    sequenceNumber: Int,
    handoff: FeatureTaskRuntimeCommitPushHandoff,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    recordCommit: (commitSha: String, stagedPaths: List<String>) -> String?,
  ): FeatureTaskRuntimeSubtaskFinalisationResult {
    val excluded = handoff.changedPaths.filter(::isGovernedSpecPath).distinct().sorted()
    val stageable = handoff.changedPaths.filter(String::isNotBlank)
      .filterNot(::isGovernedSpecPath)
      .distinct()
      .sorted()
    if (excluded.isNotEmpty()) record(specExclusionRecord(identity, excluded))

    val snapshot = gitOperations.captureIndexState(repoRoot, stageable)
    if (!snapshot.ok) return blocked("the pre-finalisation index could not be captured (${snapshot.error})")
    val staged = gitOperations.stagePaths(repoRoot, stageable)
    if (!staged.ok) return blocked(restoring(staged.error, stageable, snapshot.value.orEmpty()))

    val decision = decide(branch, identity, durableCommitSha, sequenceNumber)
    val rewrites = decision is FeatureTaskRuntimeSubtaskCommitDecision.Amend
    val message = FeatureTaskRuntimeCheckpointMessage.finalise(handoff.outcomeMessage, metadata, identity)

    val commit = gitOperations.writeSubtaskCommitPreservingHistory(
      repoRoot = repoRoot,
      decision = decision,
      identity = identity,
      message = message,
      allowUnchangedIndex = true,
      record = record,
    )
    if (!commit.ok) return blocked(restoring(commit.error, stageable, snapshot.value.orEmpty()))
    val commitSha = commit.value.orEmpty().trim().takeIf(String::isNotBlank)
      ?: return blocked(restoring("the finalisation commit returned an empty sha", stageable, snapshot.value.orEmpty()))

    recordCommit(commitSha, stageable)?.let { return FeatureTaskRuntimeSubtaskFinalisationResult.Blocked(it) }

    val forcedWithLease = rewrites && remoteDiverged(branch, commitSha)
    val pushFailure = push(branch, identity, commitSha, forcedWithLease)
    if (pushFailure != null) return blocked(pushFailure)
    return FeatureTaskRuntimeSubtaskFinalisationResult.Finalised(
      commitSha = commitSha,
      stagedPaths = stageable,
      excludedSpecPaths = excluded,
      forcedWithLease = forcedWithLease,
    )
  }

  private fun decide(
    branch: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    durableCommitSha: String?,
    sequenceNumber: Int,
  ): FeatureTaskRuntimeSubtaskCommitDecision {
    val headSha = gitOperations.headCommitSha(repoRoot).takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val unpushed = gitOperations.localBranchHasUnpushedCommits(repoRoot, branch)
    return FeatureTaskRuntimeSubtaskCommitResolver.decide(
      identity = identity,
      durableCommitSha = durableCommitSha,
      head = FeatureTaskRuntimeSubtaskCommitHeadState(
        sha = headSha,
        commitMessage = if (durableCommitSha == null && headSha != null) headMessage() else null,
        isUnpushed = unpushed.ok && unpushed.value.orEmpty().trim().equals("true", ignoreCase = true),
      ),
      sequenceNumber = sequenceNumber,
    )
  }

  private fun headMessage(): String? =
    gitOperations.headCommitMessage(repoRoot).takeIf { it.ok }?.value

  /**
   * Whether the remote tip has left the lineage of the commit finalisation just wrote. Read after the
   * write and only for an amend, so the lease is reachable from a rewrite of this subtask's own history
   * and never as a retry of a rejected create-path push: a created commit keeps the remote tip as an
   * ancestor unless someone else pushed, and that case must stay a plain rejected push. An ancestry
   * check that could not run reads as no divergence, so an unreadable remote never escalates to a
   * force.
   */
  private fun remoteDiverged(branch: String, commitSha: String): Boolean {
    val remoteTip = gitOperations.resolveCommit(repoRoot, "origin/$branch")
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank) ?: return false
    val ancestor = gitOperations.isCommitAncestor(repoRoot, remoteTip, commitSha)
    return ancestor.ok && ancestor.value.orEmpty().trim().equals("false", ignoreCase = true)
  }

  private fun push(
    branch: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    commitSha: String,
    withLease: Boolean,
  ): String? {
    if (!withLease) {
      val pushed = gitOperations.pushBranch(repoRoot, branch)
      return if (pushed.ok) null else "the finalised subtask commit '$commitSha' could not be pushed (${pushed.error})"
    }
    record(forceWithLeaseRecord(identity, branch, commitSha))
    val pushed = gitOperations.pushBranchWithLease(repoRoot, branch)
    if (pushed.ok) return null
    record(leaseAbortRecord(identity, branch, pushed.error))
    return "the reopened subtask's amended commit '$commitSha' was not pushed: the lease on 'origin/$branch' " +
      "no longer holds the value this repository last observed, so the remote moved under this run " +
      "(${pushed.error}). The remote is untouched; reconcile the branch before resuming."
  }

  private fun restoring(error: String, paths: List<String>, snapshot: String): String {
    val restored = gitOperations.restoreIndexState(repoRoot, paths, snapshot)
    return if (restored.ok) {
      "$error; the pre-finalisation index was restored and the working tree is unchanged"
    } else {
      "$error; the pre-finalisation index could NOT be restored (${restored.error}) — inspect " +
        "`git status` before committing anything yourself"
    }
  }

  private fun blocked(reason: String) = FeatureTaskRuntimeSubtaskFinalisationResult.Blocked(
    "needs_human: subtask finalisation could not complete because $reason.",
  )

  companion object {
    /**
     * The agent-supplied half of `commit_push_result`, read before any git write so a non-conforming
     * payload costs nothing to reject. A blank outcome message is the load-bearing refusal: without it
     * the finalisation would publish the provisional checkpoint subject as the deliverable commit.
     */
    fun readHandoff(envelope: Map<String, Any?>): FeatureTaskRuntimeCommitPushHandoffResult {
      val result = commitPushResult(envelope)
        ?: return invalid("`produced_outputs.$COMMIT_PUSH_RESULT_KEY` is absent")
      val message = result[OUTCOME_MESSAGE_KEY]?.toString()?.trim()?.takeIf(String::isNotBlank)
        ?: return invalid("`$COMMIT_PUSH_RESULT_KEY.$OUTCOME_MESSAGE_KEY` is missing or blank")
      if (!result.containsKey(CHANGED_PATHS_KEY)) {
        return invalid("`$COMMIT_PUSH_RESULT_KEY.$CHANGED_PATHS_KEY` is absent")
      }
      val paths = (result[CHANGED_PATHS_KEY] as? List<*>)
        ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
        ?: return invalid("`$COMMIT_PUSH_RESULT_KEY.$CHANGED_PATHS_KEY` is not a list of paths")
      return FeatureTaskRuntimeCommitPushHandoffResult.Valid(
        FeatureTaskRuntimeCommitPushHandoff(outcomeMessage = message, changedPaths = paths),
      )
    }

    /**
     * Writes the runtime-captured post-amend sha into the envelope the phase record persists, so
     * `commit_push_result.commit_sha` and the goal-continuation outcome derived from that same record
     * are one value by construction rather than two readings that could disagree.
     */
    fun withCommitSha(envelope: Map<String, Any?>, commitSha: String): Map<String, Any?> {
      val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])?.toMutableMap()
        ?: return envelope
      val result = JsonSupport.anyToStringAnyMap(produced[COMMIT_PUSH_RESULT_KEY])?.toMutableMap()
        ?: return envelope
      result[COMMIT_SHA_KEY] = commitSha
      produced[COMMIT_PUSH_RESULT_KEY] = result
      return envelope.toMutableMap().apply { this["produced_outputs"] = produced }
    }

    private fun commitPushResult(envelope: Map<String, Any?>): Map<String, Any?>? =
      JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])?.let { produced ->
        JsonSupport.anyToStringAnyMap(produced[COMMIT_PUSH_RESULT_KEY])
      } ?: JsonSupport.anyToStringAnyMap(envelope[COMMIT_PUSH_RESULT_KEY])

    private fun invalid(detail: String) = FeatureTaskRuntimeCommitPushHandoffResult.Invalid(
      "needs_human: commit_push completed but $detail. The runtime performs the commit and push from " +
        "that payload, so without it the subtask would publish the provisional checkpoint subject. " +
        "Re-run commit_push emitting `$COMMIT_PUSH_RESULT_KEY` with a non-blank `$OUTCOME_MESSAGE_KEY` " +
        "and an enumerated `$CHANGED_PATHS_KEY`.",
    )

    private fun isGovernedSpecPath(path: String): Boolean = path.trim().startsWith(GOVERNED_SPEC_ROOT)

    /** Names the seam, the value used, the value expected, and the cause, per docs/observability-policy.md. */
    private fun specExclusionRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, paths: List<String>) =
      "seam=FeatureTaskRuntimeSubtaskFinalisation.finalise value_used='staged path set without " +
        "${paths.joinToString(", ")}' value_expected=the agent's enumerated path set for " +
        "'${identity.issueKey}/${identity.subtaskId}' cause=governed feature specs are workflow input, " +
        "never subtask deliverable output, so they are dropped from the staged set and left dirty locally"

    /** Names the seam, the value used, the value expected, and the cause, per docs/observability-policy.md. */
    private fun forceWithLeaseRecord(
      identity: FeatureTaskRuntimeSubtaskCommitIdentity,
      branch: String,
      commitSha: String,
    ) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='git push --force-with-lease origin " +
      "$branch' value_expected=a non-forcing push of '$commitSha' cause=subtask " +
      "'${identity.issueKey}/${identity.subtaskId}' was reopened after its commit had already been " +
      "published, so finalisation rewrote a commit the remote already carries"

    /** Names the seam, the value used, the value expected, and the cause, per docs/observability-policy.md. */
    private fun leaseAbortRecord(
      identity: FeatureTaskRuntimeSubtaskCommitIdentity,
      branch: String,
      error: String,
    ) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='an unpushed local tip' " +
      "value_expected='origin/$branch' still at the value this repository last observed for subtask " +
      "'${identity.issueKey}/${identity.subtaskId}' cause=the lease was rejected, so the remote moved " +
      "under this run and the push was abandoned without touching it ($error)"
  }
}

/**
 * One subtask, one branch commit: create it, or preserve the pre-amend commit under this subtask's own
 * checkpoint ref and then amend it. The preservation runs BEFORE the amend rewrites HEAD and its result
 * is read back, so the runtime never discards a state it could not first prove it preserved; a failed or
 * unverifiable ref write leaves HEAD exactly where it is.
 *
 * [allowUnchangedIndex] is set only by finalisation, which amends to replace a provisional message on an
 * already-committed tree. A checkpoint amending with nothing staged is a caller bug and stays refused.
 */
@Suppress("ReturnCount", "LongParameterList") // each early return is one unrecoverable preservation failure
internal fun WorkflowGitOperations.writeSubtaskCommitPreservingHistory(
  repoRoot: Path,
  decision: FeatureTaskRuntimeSubtaskCommitDecision,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  message: String,
  allowUnchangedIndex: Boolean,
  record: (String) -> Unit,
): WorkflowGitOperationResult {
  if (decision !is FeatureTaskRuntimeSubtaskCommitDecision.Amend) return createCommit(repoRoot, message)
  if (decision.recoveredFromTrailer) {
    record(FeatureTaskRuntimeSubtaskCommitResolver.trailerFallbackRecord(identity, decision.ownedHeadSha))
  }
  val refName = identity.checkpointRefName(decision.sequenceNumber)
  val existing = resolveCheckpointRef(repoRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, refName)
  if (!existing.ok) {
    return preAmendPreservationFailure(
      refName,
      "whether that ref already preserves another commit could not be determined (${existing.error})",
    )
  }
  val occupant = existing.value.orEmpty().trim()
  if (occupant.isNotBlank() && occupant != decision.ownedHeadSha) {
    return preAmendPreservationFailure(
      refName,
      "that ref already preserves '$occupant' and writing '${decision.ownedHeadSha}' over it would discard " +
        "the only reachability that commit has; the checkpoint sequence restarted, so this ref name is not " +
        "this checkpoint's to reuse",
    )
  }
  val written = updateCheckpointRef(repoRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, refName, decision.ownedHeadSha)
  if (!written.ok) return preAmendPreservationFailure(refName, written.error)
  val resolved = resolveCheckpointRef(repoRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, refName)
  val preserved = resolved.value.orEmpty().trim()
  if (!resolved.ok || preserved != decision.ownedHeadSha) {
    return preAmendPreservationFailure(
      refName,
      resolved.error.takeIf { it.isNotBlank() }
        ?: "the ref resolved to '$preserved' rather than the pre-amend commit '${decision.ownedHeadSha}'",
    )
  }
  return amendHeadCommit(repoRoot, decision.ownedHeadSha, message, allowUnchangedIndex)
}

private fun preAmendPreservationFailure(refName: String, error: String) = WorkflowGitOperationResult(
  status = "error",
  error = "the pre-amend checkpoint commit could not be preserved at '$refName' ($error); the amend " +
    "did not run and HEAD is unchanged",
)
