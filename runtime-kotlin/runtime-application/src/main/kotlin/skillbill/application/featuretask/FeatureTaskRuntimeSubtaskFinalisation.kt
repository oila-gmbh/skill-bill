package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.amendHeadCommit
import skillbill.ports.workflow.captureIndexState
import skillbill.ports.workflow.deleteCheckpointRefsUnderPrefix
import skillbill.ports.workflow.headCommitMessage
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.resolveCheckpointRef
import skillbill.ports.workflow.restoreIndexState
import skillbill.ports.workflow.stagePaths
import skillbill.ports.workflow.updateCheckpointRef
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecordSidecar
import skillbill.workflow.taskruntime.model.readCommitSubjectFromProse
import java.nio.file.Path

private const val COMMIT_PUSH_RESULT_KEY = "commit_push_result"
private const val OUTCOME_MESSAGE_KEY = "message"
private const val CHANGED_PATHS_KEY = "changed_paths"
private const val COMMIT_SHA_KEY = "commit_sha"
private const val GOVERNED_SPEC_ROOT = ".feature-specs/"
private const val GIT_PORCELAIN_MIN_LENGTH = 4
private const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3

/** The agent's half of the finalisation contract: the commit subject. `changedPaths` is advisory. */
internal data class FeatureTaskRuntimeCommitPushHandoff(
  val outcomeMessage: String,
  val changedPaths: List<String>,
)

internal sealed interface FeatureTaskRuntimeCommitPushHandoffResult

internal data class FeatureTaskRuntimeCommitPushHandoffValid(
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
) : FeatureTaskRuntimeCommitPushHandoffResult

internal data class FeatureTaskRuntimeCommitPushHandoffInvalid(
  val reason: String,
) : FeatureTaskRuntimeCommitPushHandoffResult

internal sealed interface FeatureTaskRuntimeSubtaskFinalisationResult

internal data class FeatureTaskRuntimeSubtaskFinalised(
  val commitSha: String,
  val stagedPaths: List<String>,
  val excludedSpecPaths: List<String>,
  val forcedWithLease: Boolean,
) : FeatureTaskRuntimeSubtaskFinalisationResult

internal data class FeatureTaskRuntimeSubtaskFinalisationBlocked(
  val reason: String,
) : FeatureTaskRuntimeSubtaskFinalisationResult

/**
 * SKILL-190: the runtime half of `commit_push`.
 *
 * The agent supplies the commit subject; every git write below is the runtime's. Staging sweeps every
 * dirty non-ignored worktree path except governed `.feature-specs/` inputs (gitignored paths never
 * appear in porcelain status, so they stay out). Order is load-bearing: the handoff message is
 * validated before anything is staged, so a rejected finalisation leaves the repository exactly as
 * the agent left it; the message is applied in the same amend that stages the content, so no commit
 * ever reaches a pushed state carrying the provisional subject; and the sha is captured after that
 * amend, so the value threaded into the manifest is the final one rather than an intermediate.
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
  private val recordCommit: (commitSha: String, stagedPaths: List<String>) -> String?,
) {
  @Suppress(
    "ReturnCount",
    "LongParameterList",
  ) // each early return is one failure the caller must see as a distinct block
  fun finalise(
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    durableCommitSha: String?,
    sequenceNumber: Int,
    handoff: FeatureTaskRuntimeCommitPushHandoff,
    metadata: FeatureTaskRuntimeCheckpointMetadata,
    manifestCommitSha: String? = null,
  ): FeatureTaskRuntimeSubtaskFinalisationResult {
    val branch = metadata.branch
    val dirtyOrError = dirtyImplementationPaths()
    if (dirtyOrError is DirtyPathsError) return blocked(dirtyOrError.reason)
    val dirtyPaths = (dirtyOrError as DirtyPaths).paths
    val excluded = dirtyPaths.filter(::isGovernedSpecPath).distinct().sorted()
    val stageable = dirtyPaths.filterNot(::isGovernedSpecPath).distinct().sorted()
    if (excluded.isNotEmpty()) record(specExclusionRecord(identity, excluded))
    // Nothing stageable refuses only when the worktree still holds dirt finalisation will not stage:
    // governed `.feature-specs/` inputs. That is the case worth blocking, because the agent's
    // deliverable is then unaccounted for while an unchanged tree would publish anyway. A worktree
    // with no dirty non-ignored paths at all has nothing that could be silently dropped — the
    // checkpoint commit already carries the deliverable — so finalisation applies the final message
    // under allowUnchangedIndex and completes. Blocking that case regressed every run whose phases
    // left a clean tree.
    val alreadyCommitted = stageable.isEmpty()
    if (alreadyCommitted && excluded.isNotEmpty()) return blocked(emptyStageableReason(excluded))

    // No observability record here: an already-committed clean tree is ordinary completion, not a
    // fallback, degradation, or swallowed failure.
    val restoreState: String
    if (alreadyCommitted) {
      restoreState = ""
    } else {
      val snapshot = gitOperations.captureIndexState(repoRoot, stageable)
      if (!snapshot.ok) return blocked("the pre-finalisation index could not be captured (${snapshot.error})")
      val staged = gitOperations.stagePaths(repoRoot, stageable)
      if (!staged.ok) return blocked(restoring(staged.error, stageable, snapshot.value.orEmpty()))
      restoreState = snapshot.value.orEmpty()
    }

    val decision = decide(branch, identity, durableCommitSha, sequenceNumber)
    val rewrites = decision is FeatureTaskRuntimeSubtaskCommitAmend
    val message = FeatureTaskRuntimeCheckpointMessage.finalise(handoff.outcomeMessage, metadata, identity)

    val commit = gitOperations.writeSubtaskCommitPreservingHistory(
      repoRoot = repoRoot,
      decision = decision,
      identity = identity,
      message = message,
      allowUnchangedIndex = true,
      record = record,
    )
    if (!commit.ok) return blocked(restoring(commit.error, stageable, restoreState))
    val commitSha = commit.value.orEmpty().trim().takeIf(String::isNotBlank)
      ?: return blocked(restoring("the finalisation commit returned an empty sha", stageable, restoreState))

    recordCommit(commitSha, stageable)?.let { return FeatureTaskRuntimeSubtaskFinalisationBlocked(it) }

    val forcedWithLease = rewrites && remoteDiverged(branch, commitSha)
    val pushFailure = push(branch, identity, commitSha, forcedWithLease)
    if (pushFailure != null) return blocked(pushFailure)
    if (!manifestCommitSha.isNullOrBlank()) {
      gitOperations.pruneSubtaskCheckpointRefs(
        repoRoot = repoRoot,
        request = FeatureTaskRuntimeCheckpointRefPruneRequest(
          issueKey = identity.issueKey,
          subtaskId = identity.subtaskId,
          manifestCommitSha = manifestCommitSha,
          featureBranch = branch,
        ),
        record = record,
      )
    }
    return FeatureTaskRuntimeSubtaskFinalised(
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

  private fun headMessage(): String? = gitOperations.headCommitMessage(repoRoot).takeIf { it.ok }?.value

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

  /**
   * Every dirty non-ignored path in the worktree. Porcelain status already omits gitignored files, so
   * a sweep here matches "stage everything dirty except ignored". Governed specs stay in the list for
   * exclusion reporting and are filtered out before staging.
   */
  private fun dirtyImplementationPaths(): DirtyPathsResult {
    val status = gitOperations.worktreeStatus(repoRoot)
    if (!status.ok) {
      return DirtyPathsError("the worktree status could not be read before staging (${status.error})")
    }
    val paths = parseGitPorcelainPaths(status.value.orEmpty())
      .map(::normalizeRepoPath)
      .filter { it.isNotBlank() }
      .distinct()
      .sorted()
    return DirtyPaths(paths)
  }

  private sealed interface DirtyPathsResult
  private data class DirtyPaths(val paths: List<String>) : DirtyPathsResult
  private data class DirtyPathsError(val reason: String) : DirtyPathsResult

  private fun blocked(reason: String) = FeatureTaskRuntimeSubtaskFinalisationBlocked(
    "needs_human: subtask finalisation could not complete because $reason.",
  )

  @Suppress("TooManyFunctions")
  companion object {
    /**
     * The agent-supplied half of `commit_push_result`, read before any git write so a non-conforming
     * payload costs nothing to reject. A blank outcome message is the load-bearing refusal: without it
     * the finalisation would publish the provisional checkpoint subject as the deliverable commit.
     */
    fun readHandoffFromProse(prose: String): FeatureTaskRuntimeCommitPushHandoffResult {
      val message = readCommitSubjectFromProse(prose)
        ?: return invalid("<<<COMMIT_SUBJECT>>> delimited commit subject is missing or blank")
      return FeatureTaskRuntimeCommitPushHandoffValid(
        FeatureTaskRuntimeCommitPushHandoff(outcomeMessage = message, changedPaths = emptyList()),
      )
    }

    fun withCommitShaSidecar(commitSubject: String, commitSha: String): FeatureTaskRuntimePhaseRecordSidecar =
      FeatureTaskRuntimePhaseRecordSidecar(
        commitSubject = commitSubject,
        commitSha = commitSha,
      )

    fun readHandoff(envelope: Map<String, Any?>): FeatureTaskRuntimeCommitPushHandoffResult {
      val result = commitPushResult(envelope)
        ?: return invalid("`produced_outputs.$COMMIT_PUSH_RESULT_KEY` is absent")
      val message = result[OUTCOME_MESSAGE_KEY]?.toString()?.trim()?.takeIf(String::isNotBlank)
        ?: return invalid("`$COMMIT_PUSH_RESULT_KEY.$OUTCOME_MESSAGE_KEY` is missing or blank")
      val paths = when {
        !result.containsKey(CHANGED_PATHS_KEY) -> emptyList()
        else -> changedPaths(result) ?: return invalid(
          "`$COMMIT_PUSH_RESULT_KEY.$CHANGED_PATHS_KEY` is not a list of paths",
        )
      }
      return FeatureTaskRuntimeCommitPushHandoffValid(
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

    private fun changedPaths(result: Map<String, Any?>): List<String>? =
      (result[CHANGED_PATHS_KEY] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }

    private fun commitPushResult(envelope: Map<String, Any?>): Map<String, Any?>? =
      JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])?.let { produced ->
        JsonSupport.anyToStringAnyMap(produced[COMMIT_PUSH_RESULT_KEY])
      } ?: JsonSupport.anyToStringAnyMap(envelope[COMMIT_PUSH_RESULT_KEY])

    private fun invalid(detail: String) = FeatureTaskRuntimeCommitPushHandoffInvalid(
      "needs_human: commit_push completed but $detail. The runtime performs the commit and push from " +
        "that payload, so without it the subtask would publish the provisional checkpoint subject. " +
        "Re-run commit_push emitting `$COMMIT_PUSH_RESULT_KEY` with a non-blank `$OUTCOME_MESSAGE_KEY` " +
        "and an enumerated `$CHANGED_PATHS_KEY`.",
    )

    private fun isGovernedSpecPath(path: String): Boolean = normalizeRepoPath(path).startsWith(GOVERNED_SPEC_ROOT)

    private fun normalizeRepoPath(path: String): String = path.trim().removeSurrounding("\"").removePrefix("./")

    private fun parseGitPorcelainPaths(output: String): List<String> = output
      .lineSequence()
      .map(String::trimEnd)
      .filter { line -> line.length >= GIT_PORCELAIN_MIN_LENGTH }
      .map(::pathFromPorcelainLine)
      .filter(String::isNotBlank)
      .toList()

    private fun pathFromPorcelainLine(line: String): String {
      val pathPart = when {
        line.length >= 2 &&
          line[0] != ' ' &&
          line[1] == ' ' &&
          (
            line.length < GIT_PORCELAIN_STATUS_PREFIX_LENGTH + 1 ||
              line[GIT_PORCELAIN_STATUS_PREFIX_LENGTH] != ' '
            ) -> line.drop(2)
        else -> line.drop(GIT_PORCELAIN_STATUS_PREFIX_LENGTH)
      }
      return pathPart.substringAfterLast(" -> ").trim().removeSurrounding("\"")
    }

    /**
     * The empty-stageable refusal, load-bearing for the same reason the blank-message one is. Staging an
     * empty pathspec set is a silent no-op, and finalisation amends with `allowUnchangedIndex`, so the
     * amend would succeed on the unchanged checkpoint tree and the subtask would report a commit sha
     * while every post-checkpoint edit stayed uncommitted and out of the deliverable.
     */
    private fun emptyStageableReason(excluded: List<String>): String {
      val cause = if (excluded.isEmpty()) {
        "the worktree has no dirty non-ignored paths"
      } else {
        "the only dirty paths are governed `$GOVERNED_SPEC_ROOT` inputs " +
          "(${excluded.joinToString(", ")}), which finalisation never stages"
      }
      return "$cause, so there is nothing to stage. Finalisation would otherwise publish the " +
        "already-committed checkpoint tree with no deliverable content"
    }

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
    private fun leaseAbortRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, branch: String, error: String) =
      "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='an unpushed local tip' " +
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
 * Finalisation also reclaims the subtask checkpoint namespace when a prior abandoned run left a foreign
 * occupant on the target ref; mid-run checkpoint amends still refuse that overwrite.
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
  if (decision !is FeatureTaskRuntimeSubtaskCommitAmend) return createCommit(repoRoot, message)
  if (decision.recoveredFromTrailer) {
    record(FeatureTaskRuntimeSubtaskCommitResolver.trailerFallbackRecord(identity, decision.ownedHeadSha))
  }
  if (decision.rewritesPublishedHistory) {
    record(FeatureTaskRuntimeSubtaskCommitResolver.publishedHistoryRewriteRecord(identity, decision.ownedHeadSha))
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
    if (!allowUnchangedIndex) {
      return preAmendPreservationFailure(
        refName,
        "that ref already preserves '$occupant' and writing '${decision.ownedHeadSha}' over it would discard " +
          "the only reachability that commit has; the checkpoint sequence restarted, so this ref name is not " +
          "this checkpoint's to reuse",
      )
    }
    val prefix = featureTaskRuntimeSubtaskCheckpointRefPrefix(identity.issueKey, identity.subtaskId)
    val swept = deleteCheckpointRefsUnderPrefix(repoRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, prefix)
    if (!swept.ok) {
      return preAmendPreservationFailure(
        refName,
        "stale checkpoint refs under '$prefix' could not be swept before reclaiming the ref (${swept.error})",
      )
    }
    record(
      "seam=writeSubtaskCommitPreservingHistory value_used='swept ${swept.value.orEmpty()} stale checkpoint " +
        "ref(s) under $prefix (foreign occupant $occupant)' value_expected=checkpoint ref '$refName' free for " +
        "pre-amend '${decision.ownedHeadSha}' cause=commit_push finalisation reclaims the subtask checkpoint " +
        "namespace when a prior run left a foreign occupant",
    )
  }
  val written =
    updateCheckpointRef(repoRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, refName, decision.ownedHeadSha)
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
