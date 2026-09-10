package skillbill.application.review

import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.ReviewLaneReviewDisposition

internal fun aggregateInlineChunkOutcomes(outcomes: List<ParallelReviewLaneOutcome>): ParallelReviewLaneOutcome {
  val first = outcomes.first()
  return first.copy(
    success = outcomes.all { it.success },
    rawOutput = outcomes.joinToString("\n\n") { it.rawOutput }.trim(),
    failureReason = outcomes.firstNotNullOfOrNull { it.failureReason },
    droppedCandidateDiagnostic = outcomes.firstNotNullOfOrNull { it.droppedCandidateDiagnostic },
    budgetOutcome = outcomes.firstNotNullOfOrNull { it.budgetOutcome },
    accounting = mergeInlineChunkAccounting(outcomes),
    specialistAccounting = emptyList(),
    findings = outcomes.flatMap { it.findings },
    interrupted = outcomes.any { it.interrupted },
    reviewDisposition = aggregateInlineChunkDisposition(outcomes),
    unreviewedSegmentIds = outcomes.flatMap { it.unreviewedSegmentIds }.distinct(),
    budgetDimension = outcomes.firstNotNullOfOrNull { it.budgetDimension },
    unreviewedUnits = outcomes.flatMap { it.unreviewedUnits }.distinct(),
    rejectedCandidateCount = outcomes.sumOf { it.rejectedCandidateCount },
    unboundSeam = outcomes.firstNotNullOfOrNull { it.unboundSeam },
  )
}

private fun mergeInlineChunkAccounting(outcomes: List<ParallelReviewLaneOutcome>): ReviewLaneAccounting? {
  val records = outcomes.mapNotNull { it.accounting }
  if (records.isEmpty()) return null
  val merged = records.drop(1).fold(records.first()) { accumulated, next ->
    accumulated.copy(
      launchBytes = accumulated.launchBytes + next.launchBytes,
      authorizedReadCount = accumulated.authorizedReadCount + next.authorizedReadCount,
      refusedOperationCount = accumulated.refusedOperationCount + next.refusedOperationCount,
      refusals = (accumulated.refusals + next.refusals).distinct(),
      evidenceBytes = accumulated.evidenceBytes + next.evidenceBytes,
      expansions = (accumulated.expansions + next.expansions).distinct(),
      toolCalls = accumulated.toolCalls + next.toolCalls,
      modelTurns = accumulated.modelTurns + next.modelTurns,
      resultBytes = accumulated.resultBytes + next.resultBytes,
      requiredEvidenceUnits = accumulated.requiredEvidenceUnits + next.requiredEvidenceUnits,
      deliveredEvidenceUnits = accumulated.deliveredEvidenceUnits + next.deliveredEvidenceUnits,
      remainingEvidence = (accumulated.remainingEvidence + next.remainingEvidence).distinct(),
      evidenceRequests = accumulated.evidenceRequests + next.evidenceRequests,
    )
  }
  val anchor = outcomes.first().accounting
  val disposition = aggregateInlineChunkDisposition(outcomes)
  val terminalOutcome = outcomes.firstNotNullOfOrNull { it.accounting?.terminalOutcome }
  val terminalStatus = terminalOutcome?.let {
    outcomes.first { outcome -> outcome.accounting?.terminalOutcome != null }.accounting?.terminalStatus
  } ?: when (disposition) {
    ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
    else -> anchor?.terminalStatus ?: merged.terminalStatus
  }
  return anchor?.copy(
    launchBytes = merged.launchBytes,
    authorizedReadCount = merged.authorizedReadCount,
    refusedOperationCount = merged.refusedOperationCount,
    refusals = merged.refusals,
    evidenceBytes = merged.evidenceBytes,
    expansions = merged.expansions,
    toolCalls = merged.toolCalls,
    modelTurns = merged.modelTurns,
    resultBytes = merged.resultBytes,
    requiredEvidenceUnits = merged.requiredEvidenceUnits,
    deliveredEvidenceUnits = merged.deliveredEvidenceUnits,
    remainingEvidence = merged.remainingEvidence,
    evidenceRequests = merged.evidenceRequests,
    terminalStatus = terminalStatus,
    terminalOutcome = terminalOutcome,
    reviewDisposition = disposition,
    unreviewedSegmentIds = outcomes.flatMap { it.accounting?.unreviewedSegmentIds.orEmpty() }.distinct(),
    budgetDimension = outcomes.firstNotNullOfOrNull { it.accounting?.budgetDimension },
    unreviewedUnits = outcomes.flatMap { it.accounting?.unreviewedUnits.orEmpty() }.distinct(),
  ) ?: merged.copy(
    terminalStatus = terminalStatus,
    terminalOutcome = terminalOutcome,
    reviewDisposition = disposition,
    unreviewedSegmentIds = outcomes.flatMap { it.accounting?.unreviewedSegmentIds.orEmpty() }.distinct(),
    budgetDimension = outcomes.firstNotNullOfOrNull { it.accounting?.budgetDimension },
    unreviewedUnits = outcomes.flatMap { it.accounting?.unreviewedUnits.orEmpty() }.distinct(),
  )
}

private fun aggregateInlineChunkDisposition(outcomes: List<ParallelReviewLaneOutcome>): ReviewLaneReviewDisposition? {
  val dispositions = outcomes.mapNotNull { it.reviewDisposition }
  if (dispositions.isEmpty()) return outcomes.first().reviewDisposition
  return if (dispositions.all { it == ReviewLaneReviewDisposition.COMPLETE }) {
    ReviewLaneReviewDisposition.COMPLETE
  } else {
    ReviewLaneReviewDisposition.INCOMPLETE
  }
}
