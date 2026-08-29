package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.normalizedUnaddressedFindingCategory
import skillbill.goalrunner.model.normalizedUnaddressedFindingSeverity
import skillbill.review.ReviewFindingActionability
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.withStableFindingRefs

internal data class GoalSubtaskReviewOutputOutcome(
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
)

internal data class UnaddressedFindingLedgerScope(
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
  val reviewPassNumber: Int,
)

internal object GoalSubtaskReviewSummaryReducer {
  internal const val REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES: Int =
    GoalSubtaskReviewVerificationRejection.REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES

  fun fromOutput(
    output: Map<String, Any?>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskReviewCompactFinding> {
    return GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output, recordedVerdicts)
      .filter { finding ->
        ReviewFindingActionability.isActionable(finding.claimVerdict, finding.scopeDisposition)
      }
      .map { finding ->
        GoalSubtaskReviewCompactFinding(
          severity = finding.severity,
          label = finding.compactLabel,
          text = GoalSubtaskReviewSummarySanitize.sanitize(finding.message),
          findingId = finding.findingId,
        )
      }
      .groupBy { finding ->
        finding.findingId?.trim()?.lowercase()?.takeIf(String::isNotBlank)
          ?: finding.label.lowercase()
      }
      .values
      .map { sameKeyFindings ->
        sameKeyFindings.minByOrNull(GoalSubtaskReviewSummarySanitize::severityRank)
          ?: error("A grouped compact review summary must contain at least one finding.")
      }
      .let(::withStableFindingRefs)
  }

  fun unaddressedFindings(
    output: Map<String, Any?>,
    scope: UnaddressedFindingLedgerScope,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<UnaddressedFinding> {
    val reviewRunId = GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf(output)
    return GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output, recordedVerdicts)
      .mapIndexed { index, finding ->
        UnaddressedFinding(
          issueKey = scope.issueKey,
          subtaskId = scope.subtaskId,
          workflowId = scope.workflowId,
          reviewPassNumber = scope.reviewPassNumber,
          findingOrdinal = index + 1,
          severity = normalizedUnaddressedFindingSeverity(finding.severity),
          issueCategory = normalizedUnaddressedFindingCategory(finding.issueCategory),
          location = finding.location,
          summary = finding.message,
          reviewRunId = reviewRunId,
          findingId = finding.findingId,
          claimVerdict = finding.claimVerdict,
          scopeDisposition = finding.scopeDisposition,
          citations = finding.citations,
          severityAdjustment = finding.severityAdjustment,
        )
      }
  }

  fun unresolvedCount(output: Map<String, Any?>, recordedVerdicts: List<ReviewFindingVerdict> = emptyList()): Int =
    fromOutput(output, recordedVerdicts)
      .count(GoalSubtaskReviewCompactFinding::blocksAdvance)

  fun outcomeFor(
    output: Map<String, Any?>,
    findings: List<GoalSubtaskReviewCompactFinding> = fromOutput(output),
  ): GoalSubtaskReviewOutputOutcome {
    val advanceBlockingCount = findings.count(GoalSubtaskReviewCompactFinding::blocksAdvance)
    val hasOnlyNonBlockingFindings = findings.isNotEmpty() && advanceBlockingCount == 0
    val verdict = reviewPassVerdict(output, findings, advanceBlockingCount, hasOnlyNonBlockingFindings)
    return GoalSubtaskReviewOutputOutcome(
      verdict = verdict,
      unresolvedFindingCount = when {
        advanceBlockingCount > 0 -> advanceBlockingCount
        hasOnlyNonBlockingFindings ||
          verdict == FeatureTaskRuntimeVerdict.APPROVED ||
          verdict == FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER -> 0
        else -> 1
      },
    )
  }

  fun commitFocusedAccounting(output: Map<String, Any?>): GoalSubtaskCommitFocusedAccounting? =
    output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("commit_focused_accounting")
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.let { GoalSubtaskCommitFocusedAccounting.fromArtifactMap(it, "produced_outputs.commit_focused_accounting") }

  fun rejectedVerificationFindings(
    verifyOutput: Map<String, Any?>,
    reviewOutput: Map<String, Any?>,
    scope: UnaddressedFindingLedgerScope,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
    truncationRecords: MutableList<String>? = null,
  ): List<UnaddressedFinding> = GoalSubtaskReviewVerificationRejection.rejectedVerificationFindings(
    verifyOutput,
    reviewOutput,
    scope,
    recordedVerdicts,
    truncationRecords,
  )

  fun reviewFindingOutcomes(
    supersededFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ): List<ReviewFindingOutcomeRecord> = GoalSubtaskReviewOutcomeDispositionReduction.reviewFindingOutcomes(
    supersededFindings,
    currentFindings,
    blockerDispositions,
  )

  fun blockerDispositions(
    output: Map<String, Any?>,
    priorBlockerFindingIds: List<String> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> =
    GoalSubtaskReviewOutcomeDispositionReduction.blockerDispositions(output, priorBlockerFindingIds)

  fun refutedBlockerSupersedes(
    priorFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> = GoalSubtaskReviewOutcomeDispositionReduction.refutedBlockerSupersedes(
    priorFindings,
    currentFindings,
    recordedVerdicts,
  )
}
