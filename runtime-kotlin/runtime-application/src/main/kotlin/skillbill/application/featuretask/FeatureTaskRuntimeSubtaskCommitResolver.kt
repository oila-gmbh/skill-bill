package skillbill.application.featuretask

internal sealed interface FeatureTaskRuntimeSubtaskCommitDecision

internal data object FeatureTaskRuntimeSubtaskCommitCreate : FeatureTaskRuntimeSubtaskCommitDecision

internal data class FeatureTaskRuntimeSubtaskCommitAmend(
  val ownedHeadSha: String,
  val sequenceNumber: Int,
  val recoveredFromTrailer: Boolean,
  val rewritesPublishedHistory: Boolean,
) : FeatureTaskRuntimeSubtaskCommitDecision

internal data class FeatureTaskRuntimeSubtaskCommitHeadState(
  val sha: String?,
  val commitMessage: String?,
  val isUnpushed: Boolean,
)

internal object FeatureTaskRuntimeSubtaskCommitResolver {
  fun decide(
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    durableCommitSha: String?,
    head: FeatureTaskRuntimeSubtaskCommitHeadState,
    sequenceNumber: Int,
  ): FeatureTaskRuntimeSubtaskCommitDecision {
    val headSha = head.sha?.trim()?.takeIf(String::isNotBlank)
      ?: return FeatureTaskRuntimeSubtaskCommitCreate
    return decideWithKnownHead(identity, durableCommitSha, head, headSha, sequenceNumber)
  }

  private fun decideWithKnownHead(
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    durableCommitSha: String?,
    head: FeatureTaskRuntimeSubtaskCommitHeadState,
    headSha: String,
    sequenceNumber: Int,
  ): FeatureTaskRuntimeSubtaskCommitDecision {
    val durable = durableCommitSha?.trim()?.takeIf(String::isNotBlank)
    val ownsPublishedHead = durable != null && durable == headSha
    if (!head.isUnpushed && !ownsPublishedHead) return FeatureTaskRuntimeSubtaskCommitCreate
    if (durable != null) {
      return amendOrCreateFromDurable(durable, headSha, head, sequenceNumber)
    }
    return amendOrCreateFromTrailer(identity, head, headSha, sequenceNumber)
  }

  private fun amendOrCreateFromDurable(
    durable: String,
    headSha: String,
    head: FeatureTaskRuntimeSubtaskCommitHeadState,
    sequenceNumber: Int,
  ): FeatureTaskRuntimeSubtaskCommitDecision = if (durable == headSha) {
    FeatureTaskRuntimeSubtaskCommitAmend(
      ownedHeadSha = headSha,
      sequenceNumber = sequenceNumber,
      recoveredFromTrailer = false,
      rewritesPublishedHistory = !head.isUnpushed,
    )
  } else {
    FeatureTaskRuntimeSubtaskCommitCreate
  }

  private fun amendOrCreateFromTrailer(
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    head: FeatureTaskRuntimeSubtaskCommitHeadState,
    headSha: String,
    sequenceNumber: Int,
  ): FeatureTaskRuntimeSubtaskCommitDecision {
    val message = head.commitMessage?.takeIf(String::isNotBlank)
      ?: return FeatureTaskRuntimeSubtaskCommitCreate
    return if (identity.matches(message)) {
      FeatureTaskRuntimeSubtaskCommitAmend(
        ownedHeadSha = headSha,
        sequenceNumber = sequenceNumber,
        recoveredFromTrailer = true,
        rewritesPublishedHistory = !head.isUnpushed,
      )
    } else {
      FeatureTaskRuntimeSubtaskCommitCreate
    }
  }

  fun trailerFallbackRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, headSha: String): String =
    "seam=FeatureTaskRuntimeSubtaskCommitResolver.decide value_used='HEAD trailer $headSha' " +
      "value_expected=durable subtask-commit pointer for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=no durable checkpoint identity recorded this subtask's commit, so the amend target was " +
      "recovered from the Skill-Bill-Subtask trailer on HEAD"

  fun publishedHistoryRewriteRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, headSha: String): String =
    "seam=writeSubtaskCommitPreservingHistory value_used='an amend of the published commit $headSha' " +
      "value_expected=an amend of an unpushed commit for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=durable state proves this subtask owns the published HEAD, so its history is rewritten in " +
      "place rather than stacked; the local branch diverges from origin until finalisation pushes it " +
      "with --force-with-lease, and a manual push before then is rejected as non-fast-forward"
}
