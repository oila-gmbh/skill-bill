package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.attemptedUnresolvedEntries
import skillbill.workflow.taskruntime.model.omittedCarriedFindings
import skillbill.workflow.taskruntime.model.withStableFindingRefs
import skillbill.workflow.taskruntime.model.withoutRefutedFindings

/**
 * The findings the last review pass carries into this round. Refs are stabilized first so a review
 * that omitted one cannot fail coverage on an identity it never had, and findings verification
 * refuted are then dropped: they are not carried, so nothing owes them an entry.
 */
internal fun featureTaskRuntimeCarriedFindings(
  reviewState: GoalSubtaskReviewState,
  refutedFindingIds: Set<String> = emptySet(),
): List<GoalSubtaskReviewCompactFinding> = withoutRefutedFindings(
  withStableFindingRefs(reviewState.passResults.lastOrNull()?.findings.orEmpty()),
  refutedFindingIds,
)

/** The carried findings this round neither closed, waived, nor declared it had tried and failed. */
internal fun featureTaskRuntimeRepairReceiptOmittedFindings(
  receipt: FeatureTaskRuntimeRepairReceipt,
  reviewState: GoalSubtaskReviewState,
  refutedFindingIds: Set<String> = emptySet(),
): List<GoalSubtaskReviewCompactFinding> =
  receipt.omittedCarriedFindings(featureTaskRuntimeCarriedFindings(reviewState, refutedFindingIds))

internal fun featureTaskRuntimeCompactFindingRef(finding: GoalSubtaskReviewCompactFinding): String =
  finding.findingId?.takeIf(String::isNotBlank)
    ?: error("Carried finding must carry a stable finding_id before coverage runs.")

internal fun featureTaskRuntimeOmittedFindingsRetryReason(omitted: List<GoalSubtaskReviewCompactFinding>): String =
  "The repair receipt left these carried findings unaccounted for under finding_id: " +
    omitted.joinToString(", ", transform = ::featureTaskRuntimeCompactFindingRef) +
    ". Continue this round: add one entry per owed ref using finding_id (aliases finding_ref, id, " +
    "and ref are accepted), or, if the fix was attempted and the finding is still open, declare " +
    "outcome 'attempted_unresolved' with unresolved_reason and the constructs you touched. A " +
    "carried finding may never be left out of the receipt."

/**
 * What a round reported it tried and could not close. The refs carry the per-finding retry budget,
 * the detail is the producer's own account for whichever surface ends up reading it: the retry
 * prompt on the first report, the operator's blocked reason on a repeat.
 */
internal class FeatureTaskRuntimeUnresolvedFindings(
  val refs: Set<String>,
  val detail: String,
) {
  val retryReason: String get() = "You reported these carried findings still open after your " +
    "attempt: $detail. You have one more attempt at each. Close it, or report it unresolved again " +
    "and the run stops for an operator instead of trying a third time. Do not silently drop it from " +
    "the receipt and do not restate the same attempt as if it were new work."
}

internal fun featureTaskRuntimeUnresolvedFindings(
  receipt: FeatureTaskRuntimeRepairReceipt,
): FeatureTaskRuntimeUnresolvedFindings? {
  val unresolved = receipt.attemptedUnresolvedEntries().ifEmpty { return null }
  return FeatureTaskRuntimeUnresolvedFindings(
    refs = unresolved.mapTo(linkedSetOf(), ::unresolvedEntryRef),
    detail = unresolved.joinToString("; ", transform = ::unresolvedEntryDetail),
  )
}

private fun unresolvedEntryRef(entry: FeatureTaskRuntimeRepairReceiptEntry): String = entry.findingId

private fun unresolvedEntryDetail(entry: FeatureTaskRuntimeRepairReceiptEntry): String =
  "${unresolvedEntryRef(entry)} (${entry.unresolvedReason.orEmpty()})"
