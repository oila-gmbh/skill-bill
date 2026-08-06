package skillbill.application.review

import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitLaneDecision
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewLaneAssembledBundle
import skillbill.review.context.model.ReviewLaneAssembledEntry
import skillbill.review.context.model.ReviewLaneBundle
import skillbill.review.context.model.ReviewLaneBundleSegment
import skillbill.review.context.model.ReviewLaneBundleSegmentation
import skillbill.review.context.model.ReviewLaneCompletionState

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

internal fun ReviewCommitLaneDecision.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "commit_sha" to commitSha,
  "order_index" to orderIndex,
  "lane" to lane,
  "disposition" to disposition.name.lowercase(),
  "reason" to reason.normalizeLineEndings(),
  "signals" to signals.sorted(),
)

internal fun ReviewCommitLaneRoutingMatrix.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "routing_digest" to routingDigest,
  "commit_shas" to commitShas,
  "lanes" to lanes,
  "decisions" to decisions.sortedWith(compareBy({ it.orderIndex }, { it.lane })).map { it.toEnvelope() },
)

internal fun ReviewLaneBundle.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "bundle_digest" to bundleDigest,
  "entries" to entries.map {
    linkedMapOf("commit_sha" to it.commitSha, "order_index" to it.orderIndex, "hunk_ids" to it.hunkIds)
  },
)

internal fun ReviewLaneAssembledBundle.toLaunchEnvelope(
  segmentation: ReviewLaneBundleSegmentation,
  completion: ReviewLaneCompletionState,
): Map<String, Any?> = linkedMapOf(
  "composition_digest" to compositionDigest,
  "lane_disposition" to completion.disposition.wireValue,
  "unreviewed_segment_ids" to completion.unreviewedSegmentIds,
  "entries" to entries.map { it.toEnvelope() },
  "segments" to segmentation.segments.map { it.toEnvelope() },
) + completion.budgetDimension?.let { mapOf("budget_dimension" to it) }.orEmpty()

internal fun ReviewLaneAssembledEntry.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "commit_sha" to commitSha,
  "parent_sha" to parentSha,
  "subject" to subject.normalizeLineEndings(),
  "order_index" to orderIndex,
  "hunk_id" to hunkId,
  "path" to hunk.path,
  "old_start" to hunk.oldStart,
  "old_count" to hunk.oldCount,
  "new_start" to hunk.newStart,
  "new_count" to hunk.newCount,
  "content" to hunk.content.normalizeLineEndings(),
)

internal fun ReviewLaneBundleSegment.toEnvelope(): Map<String, Any?> = linkedMapOf(
  "segment_id" to segmentId,
  "measured_bytes" to measuredBytes,
  "composition_digest" to compositionDigest,
  "entries" to entries.map {
    linkedMapOf(
      "commit_sha" to it.commitSha,
      "order_index" to it.orderIndex,
      "hunk_id" to it.hunkId,
      "path" to it.hunk.path,
    )
  },
)
