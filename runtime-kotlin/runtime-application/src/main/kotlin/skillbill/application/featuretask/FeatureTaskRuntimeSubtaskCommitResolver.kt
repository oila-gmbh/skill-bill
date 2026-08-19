package skillbill.application.featuretask

/**
 * SKILL-190: whether a checkpoint creates the subtask commit or amends it.
 *
 * [Amend] carries the sha the caller declares it owns, so the amend primitive is handed the ownership
 * signal rather than inferring policy from branch state.
 */
internal sealed interface FeatureTaskRuntimeSubtaskCommitDecision {
  data object Create : FeatureTaskRuntimeSubtaskCommitDecision

  data class Amend(
    val ownedHeadSha: String,
    val sequenceNumber: Int,
    val recoveredFromTrailer: Boolean,
  ) : FeatureTaskRuntimeSubtaskCommitDecision
}

/** The HEAD facts the create-or-amend decision reads. */
internal data class FeatureTaskRuntimeSubtaskCommitHeadState(
  val sha: String?,
  val commitMessage: String?,
  val isUnpushed: Boolean,
)

/**
 * The create-or-amend decision for one subtask's checkpoint commits.
 *
 * Durable workflow state is the authority: a recorded subtask-commit sha that still equals HEAD is the
 * only unambiguous proof this run owns HEAD. Only when no durable pointer exists does the decision
 * fall back to the git-visible subtask trailer, which recovers the amend target after a crash wiped
 * the pointer; that fallback is a degradation and its caller records one.
 *
 * The guard is identity-keyed, never ownership-keyed. HEAD being some runtime-written commit is not
 * enough: the previous subtask's finished commit is also runtime-written, and amending it would
 * destroy a completed deliverable on the shared branch. Everything that is not a confirmed match on
 * this subtask's own commit creates instead, and an already-published HEAD is amendable only under a
 * durable pointer that names it.
 */
internal object FeatureTaskRuntimeSubtaskCommitResolver {
  @Suppress("ReturnCount") // each early return is one disqualifying condition, flatter than nesting
  fun decide(
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    durableCommitSha: String?,
    head: FeatureTaskRuntimeSubtaskCommitHeadState,
    sequenceNumber: Int,
  ): FeatureTaskRuntimeSubtaskCommitDecision {
    val headSha = head.sha?.trim()?.takeIf(String::isNotBlank)
      ?: return FeatureTaskRuntimeSubtaskCommitDecision.Create
    val durable = durableCommitSha?.trim()?.takeIf(String::isNotBlank)
    // Rewriting a published commit is allowed exactly when durable state proves that commit is this
    // subtask's own, for every caller rather than for finalisation alone. Keying it on the caller made
    // a reopened subtask's first checkpoint create a second commit on top of its published one, which
    // finalisation then amended, so the branch ended the subtask carrying two commits. Finalisation's
    // force-with-lease push reconciles the remote for whichever commit the rewrite produced. A pushed
    // HEAD no durable pointer claims is still someone else's history and still creates.
    val ownsPublishedHead = durable != null && durable == headSha
    if (!head.isUnpushed && !ownsPublishedHead) return FeatureTaskRuntimeSubtaskCommitDecision.Create
    if (durable != null) {
      return if (durable == headSha) {
        FeatureTaskRuntimeSubtaskCommitDecision.Amend(headSha, sequenceNumber, recoveredFromTrailer = false)
      } else {
        FeatureTaskRuntimeSubtaskCommitDecision.Create
      }
    }
    val message = head.commitMessage?.takeIf(String::isNotBlank)
      ?: return FeatureTaskRuntimeSubtaskCommitDecision.Create
    return if (identity.matches(message)) {
      FeatureTaskRuntimeSubtaskCommitDecision.Amend(headSha, sequenceNumber, recoveredFromTrailer = true)
    } else {
      FeatureTaskRuntimeSubtaskCommitDecision.Create
    }
  }

  /** Names the seam, the value used, the value expected, and the cause, per docs/observability-policy.md. */
  fun trailerFallbackRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, headSha: String): String =
    "seam=FeatureTaskRuntimeSubtaskCommitResolver.decide value_used='HEAD trailer $headSha' " +
      "value_expected=durable subtask-commit pointer for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=no durable checkpoint identity recorded this subtask's commit, so the amend target was " +
      "recovered from the Skill-Bill-Subtask trailer on HEAD"
}
