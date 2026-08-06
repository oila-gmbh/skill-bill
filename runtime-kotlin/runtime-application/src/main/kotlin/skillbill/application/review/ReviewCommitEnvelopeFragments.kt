package skillbill.application.review

import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewLaneBundle

internal fun ReviewCommitUnit.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "commit_unit_id" to commitUnitId,
  "commit_sha" to commitSha,
  "parent_sha" to parentSha,
  "subject" to subject.normalizeLineEndings(),
  "order_index" to orderIndex,
  "source" to source.name.lowercase(),
  "hunk_ids" to hunkIds,
)

/** Launch-side commit metadata: identity and order only, since the bodies below carry attribution. */
internal fun ReviewCommitUnit.toAssignedEnvelope(): Map<String, Any?> = linkedMapOf(
  "commit_unit_id" to commitUnitId,
  "commit_sha" to commitSha,
  "parent_sha" to parentSha,
  "subject" to subject.normalizeLineEndings(),
  "order_index" to orderIndex,
  "source" to source.name.lowercase(),
)

internal fun ReviewCommitCoverageFact.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "base_revision" to baseRevision,
  "head_revision" to headRevision,
  "commit_count" to commitCount,
  "chain_verified" to chainVerified,
  "path_coverage_verified" to pathCoverageVerified,
  "degraded_reason" to degradedReason,
)

internal fun ReviewLaneBundle.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "bundle_digest" to bundleDigest,
  "entries" to entries.map {
    linkedMapOf("commit_sha" to it.commitSha, "order_index" to it.orderIndex, "hunk_ids" to it.hunkIds)
  },
)

/** Commit attribution travels with the body, so a worker never needs Git to place a hunk. */
internal fun ReviewChangedHunk.toCommitAttributedEnvelope(commit: ReviewCommitUnit): Map<String, Any?> =
  linkedMapOf<String, Any?>("commit_sha" to commit.commitSha, "order_index" to commit.orderIndex) + toEnvelope()
