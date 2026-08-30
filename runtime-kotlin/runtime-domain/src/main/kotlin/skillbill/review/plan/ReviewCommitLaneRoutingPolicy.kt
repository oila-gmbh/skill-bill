package skillbill.review.plan

import skillbill.review.context.model.REVIEW_ROUTING_REASON_MAX_CHARS
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitLaneDecision
import skillbill.review.context.model.ReviewCommitLaneDisposition
import skillbill.review.context.model.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.plan.model.ReviewRoutedLane

/**
 * Decides, once and finally, which commits each specialist lane sees. Relevance is never deferred:
 * every commit/lane pair leaves here as focused or skipped with a reason falsifiable against that
 * commit's own changed hunks, because no downstream worker re-decides it.
 */
object ReviewCommitLaneRoutingPolicy {
  const val REQUIRED_BASELINE_SIGNAL: String = "required-baseline"

  private const val MAX_LISTED = 6
  private const val SHORT_COMMIT_SHA_CHARS = 12

  fun route(
    units: List<ReviewCommitUnit>,
    lanes: List<ReviewRoutedLane>,
    @Suppress("UNUSED_PARAMETER")
    budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  ): ReviewCommitLaneRoutingMatrix {
    require(units.isNotEmpty()) { "Commit/lane routing requires at least one review unit." }
    require(lanes.isNotEmpty()) { "Commit/lane routing requires at least one planned lane." }
    val ordered = units.sortedBy { it.orderIndex }
    val decisions = ordered.flatMap { unit ->
      lanes.map { lane -> decide(unit, lane) }
    }
    return ReviewCommitLaneRoutingMatrix(ordered.map { it.commitSha }, lanes.map { it.laneKey }, decisions)
  }

  /**
   * Evidence is the commit's own incremental hunks — paths and changed content. The commit subject
   * is deliberately never read: a message can neither create nor excuse a lane inclusion.
   */
  private fun decide(unit: ReviewCommitUnit, lane: ReviewRoutedLane): ReviewCommitLaneDecision {
    val descriptor = lane.descriptor
    if (descriptor.required) {
      return ReviewCommitLaneDecision(
        commitSha = unit.commitSha,
        orderIndex = unit.orderIndex,
        lane = lane.laneKey,
        disposition = ReviewCommitLaneDisposition.FOCUSED,
        reason = bounded(
          "required baseline lane '${descriptor.skillName}' covers every commit; baseline coverage is never " +
            "dropped by sparse routing",
        ),
        signals = listOf(REQUIRED_BASELINE_SIGNAL),
      )
    }
    val matchedPaths = descriptor.pathSignals.filter { signal ->
      unit.hunks.any { ReviewPathMatcher.matches(it.path, signal) }
    }
    val matchedContent = descriptor.contentSignals.filter { signal ->
      unit.hunks.any { ReviewContentMatcher.contains(it.content, signal) }
    }
    val matched = matchedPaths.map { "path:$it" } + matchedContent.map { "content:$it" }
    return if (matched.isNotEmpty()) {
      ReviewCommitLaneDecision(
        commitSha = unit.commitSha,
        orderIndex = unit.orderIndex,
        lane = lane.laneKey,
        disposition = ReviewCommitLaneDisposition.FOCUSED,
        reason = bounded(
          "commit ${short(unit.commitSha)} changed evidence matching ${descriptor.area} signals " +
            "${list(matched)}",
        ),
        signals = matched.distinct(),
      )
    } else {
      ReviewCommitLaneDecision(
        commitSha = unit.commitSha,
        orderIndex = unit.orderIndex,
        lane = lane.laneKey,
        disposition = ReviewCommitLaneDisposition.SKIPPED,
        reason = bounded(
          "commit ${short(unit.commitSha)} changed ${list(unit.hunks.map(ReviewChangedHunk::path).distinct())}; " +
            "no ${descriptor.area} path signal ${list(descriptor.pathSignals)} or content signal " +
            "${list(descriptor.contentSignals)} matched those hunks",
        ),
        signals = emptyList(),
      )
    }
  }

  private fun short(commitSha: String) = commitSha.take(SHORT_COMMIT_SHA_CHARS)

  private fun list(values: List<String>): String {
    if (values.isEmpty()) return "[none declared]"
    val shown = values.sorted().take(MAX_LISTED)
    val suffix = if (values.size > MAX_LISTED) " +${values.size - MAX_LISTED} more" else ""
    return shown.joinToString(", ", prefix = "[", postfix = "$suffix]")
  }

  private fun bounded(reason: String) = if (reason.length <= REVIEW_ROUTING_REASON_MAX_CHARS) {
    reason
  } else {
    reason.take(REVIEW_ROUTING_REASON_MAX_CHARS - 1) + "…"
  }
}
