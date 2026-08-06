package skillbill.review

import skillbill.error.ReviewAggregationIntegrityError
import skillbill.review.context.model.ReviewLaneReviewDisposition

/** One lane's result as aggregation sees it: whose it is, which sequence it covered, how it ended. */
data class ReviewLaneAggregationInput(
  val lane: String,
  val commitSequenceDigest: String,
  val disposition: ReviewLaneReviewDisposition,
  val unreviewedUnits: List<String> = emptyList(),
)

/**
 * Coverage honesty for one review. A lane that ended incomplete — budget exhaustion, or a parent
 * agent run that did not succeed — is not clean
 * coverage, and the integration pass never offsets it: the integration pass reviews cross-commit
 * behavior over what the lanes *did* review, so it has nothing to say about what they did not.
 */
data class ReviewCoverageReport(
  val cleanLanes: List<String>,
  val incompleteLanes: List<ReviewLaneAggregationInput>,
  val integrationCompleted: Boolean,
  /**
   * Set when commit-focused sequencing did not apply to this review at all — an inline run, or a
   * scope carrying no reviewable commit sequence. Reported so a reader can tell "no integration
   * pass was needed here" apart from "the integration pass failed", using the scope vocabulary the
   * skip reason already names rather than a second one.
   */
  val integrationNotApplicableReason: String? = null,
) {
  val isCleanCoverage: Boolean get() = incompleteLanes.isEmpty()

  /**
   * Renders coverage as it actually stands. When a lane is incomplete the report names the units it
   * left unreviewed and says explicitly that the integration pass did not cover them, because a
   * reader who sees "integration pass completed" next to a gap will otherwise read it as closed.
   */
  fun render(): String = buildString {
    integrationNotApplicableReason?.let { reason ->
      appendLine("Commit-focused sequencing: not applicable — $reason. No integration pass was run.")
    }
    if (isCleanCoverage) {
      appendLine("Coverage: clean — every selected lane reviewed its full assigned bundle.")
      return@buildString
    }
    appendLine(
      "Coverage: NOT clean — ${incompleteLanes.size} lane(s) ended with incomplete coverage.",
    )
    incompleteLanes.sortedBy { it.lane }.forEach { lane ->
      appendLine("- ${lane.lane} left unreviewed: ${lane.unreviewedUnits.sorted().joinToString(", ")}")
    }
    if (integrationCompleted) {
      appendLine(
        "The integration pass completed over cross-commit behavior only. It did not review the " +
          "units named above and does not close this coverage gap.",
      )
    }
  }
}

object ReviewLaneAggregation {
  /**
   * Rejects a lane set aggregation cannot merge honestly. Every check fails loudly and names the
   * offending lanes: a silently dropped or doubled lane result is indistinguishable downstream from
   * a lane that genuinely found nothing.
   */
  fun requireCompleteLaneResults(
    expectedLanes: Collection<String>,
    results: List<ReviewLaneAggregationInput>,
    commitSequenceDigest: String,
  ): ReviewCoverageReport {
    val duplicates = results.groupBy { it.lane }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
      throw ReviewAggregationIntegrityError("a lane reported more than one result", duplicates.toList())
    }
    val byLane = results.associateBy { it.lane }
    val missing = expectedLanes.filterNot { it in byLane }
    if (missing.isNotEmpty()) {
      throw ReviewAggregationIntegrityError("a selected lane produced no result", missing)
    }
    val foreign = results.map { it.lane }.filterNot { it in expectedLanes }
    if (foreign.isNotEmpty()) {
      throw ReviewAggregationIntegrityError("a result names a lane that was never selected", foreign)
    }
    val mismatched = results.filter { it.commitSequenceDigest != commitSequenceDigest }.map { it.lane }
    if (mismatched.isNotEmpty()) {
      throw ReviewAggregationIntegrityError(
        "a result was minted against a different commit sequence than the one under review",
        mismatched,
      )
    }
    return ReviewCoverageReport(
      cleanLanes = results.filter { it.disposition == ReviewLaneReviewDisposition.COMPLETE }
        .map { it.lane }.sorted(),
      incompleteLanes = results.filter { it.disposition == ReviewLaneReviewDisposition.INCOMPLETE },
      integrationCompleted = false,
    )
  }
}
