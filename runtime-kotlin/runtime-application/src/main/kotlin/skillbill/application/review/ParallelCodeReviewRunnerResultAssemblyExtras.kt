package skillbill.application.review

import skillbill.application.goalrunner.planning.sha256HexUtf8
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewCommitRoutingAccounting
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewIntegrationAccounting
import skillbill.review.context.model.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.ReviewParentAnalysisConsumption
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewLaneAggregationInput

internal fun ParallelCodeReviewRunnerResultAssembly.durableIntegrationOutcome(
  reviewRunId: String?,
  commitSequenceDigest: String,
): ReviewIntegrationPassOutcome? {
  if (reviewRunId == null) return null
  val record = runtimeOwnedPersistence.optionalRead(
    seam = "ParallelCodeReviewRunner.durableIntegrationOutcome",
    expected = "optional durable review integration result",
    fallback = null,
  ) { unitOfWork -> unitOfWork.reviews.fetchIntegrationPass(reviewRunId) }
  val terminal = record
    ?.takeIf { it.commitSequenceDigest == commitSequenceDigest }
    ?.let { ReviewIntegrationTerminalOutcome.entries.firstOrNull { entry -> entry.wireValue == it.terminalOutcome } }
    ?.takeIf { it.isDurablyComplete }
  return terminal?.let {
    ReviewIntegrationPassOutcome(
      commitSequenceDigest = commitSequenceDigest,
      terminalOutcome = ReviewIntegrationTerminalOutcome.NO_OP_RESUME,
      summarizedLaneCount = 0,
    )
  }
}

internal fun ParallelCodeReviewRunnerResultAssembly.recordIntegrationBoundary(
  reviewRunId: String?,
  outcome: ReviewIntegrationPassOutcome,
) {
  if (reviewRunId == null) return
  runtimeOwnedPersistence.requiredWrite(
    seam = "ParallelCodeReviewRunner.recordIntegrationBoundary",
    expected = "runtime-owned review integration result",
  ) { unitOfWork ->
    unitOfWork.reviews.recordIntegrationPass(
      reviewRunId,
      ReviewIntegrationPassRecord(outcome.commitSequenceDigest, outcome.terminalOutcome.wireValue),
    )
  }
}

internal fun ParallelCodeReviewRunnerResultAssembly.durablyCompleteLanes(
  initial: ParallelCodeReviewInitialRun,
  packet: ReviewContextPacket,
  ranThisPass: List<ReviewLaneAggregationInput>,
): List<ReviewLaneAggregationInput> {
  val reviewRunId = initial.request.reviewRunId ?: return emptyList()
  val alreadyRan = ranThisPass.map { it.lane }.toSet()
  val notRun = packet.selectedLanes.filterNot { it in alreadyRan }
  if (notRun.isEmpty()) return emptyList()
  val completeSkills = runtimeOwnedPersistence.requiredRead(
    seam = "ParallelCodeReviewRunner.durablyCompleteLanes",
    expected = "runtime-owned review lane dispositions",
  ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    .filter { it.reviewDisposition == ReviewRunLaneResolver.COMPLETE_DISPOSITION }
    .map { it.laneSkillName }
    .toSet()
  return notRun.filter { it.substringAfter(':') in completeSkills }.map { lane ->
    ReviewLaneAggregationInput(
      lane = lane,
      commitSequenceDigest = packet.commitSequenceDigest,
      disposition = ReviewLaneReviewDisposition.COMPLETE,
    )
  }
}

internal fun ParallelCodeReviewRunnerResultAssembly.evidenceBoundaryAccountings(
  outcomes: ParallelReviewLaneRunResult,
): List<ReviewEvidenceBoundaryAccounting> = listOfNotNull(laneEvidenceBoundary(outcomes.lane1))

internal fun ParallelCodeReviewRunnerResultAssembly.laneEvidenceBoundary(
  outcome: ParallelReviewLaneOutcome,
): ReviewEvidenceBoundaryAccounting? {
  if (outcome.accounting?.terminalStatus == UNSUPPORTED_PROVIDER_TERMINAL_STATUS) return null
  if (outcome.accounting == null && outcome.unboundSeam == null) return null
  val accounting = outcome.accounting
  return ReviewEvidenceBoundaryAccounting(
    governedLaunchCount = if (accounting != null && accounting.terminalStatus != NO_OP_RESUME_TERMINAL_STATUS) {
      1
    } else {
      0
    },
    authorizedReadCount = accounting?.authorizedReadCount ?: 0,
    refusedOperationCount = accounting?.refusedOperationCount ?: 0,
    refusedCategories = accounting?.refusals.orEmpty().map { it.category },
    evidenceBytes = accounting?.evidenceBytes ?: 0,
    expansionCount = accounting?.expansions?.size ?: 0,
    rejectedCandidateCount = outcome.rejectedCandidateCount,
    unboundSeam = outcome.unboundSeam,
  )
}

internal fun parallelAccountingSummary(outcomes: ParallelReviewLaneRunResult): ReviewAccountingSummary? {
  val accountedLanes = listOf(outcomes.lane1)
  val specialists = accountedLanes.flatMap { it.specialistAccounting }
  if (specialists.isEmpty()) return null
  fun ReviewLaneAccounting.toInput() = ReviewAccountingInput(
    lane = lane,
    assignmentDigest = assignmentDigest,
    counters = ReviewAccountingCounters(
      launchBytes,
      evidenceBytes,
      resultBytes,
      expansions.size,
      toolCalls,
      modelTurns,
    ),
    terminalOutcome = terminalStatus,
    bundleCompositionDigest = bundleCompositionDigest,
    segmentAccounting = segmentAccounting,
    unreviewedSegmentIds = unreviewedSegmentIds,
  )
  val roots = accountedLanes.mapIndexed { index, outcome ->
    ReviewAccountingInput(
      lane = "parallel-agent-${index + 1}",
      assignmentDigest = sha256HexUtf8("parallel-agent-${index + 1}"),
      children = outcome.specialistAccounting.map { it.toInput() },
      terminalOutcome = parallelReviewLaneTerminalOutcome(outcome),
      bundleCompositionDigest = outcome.bundleCompositionDigest,
      segmentAccounting = outcome.segmentAccounting,
      unreviewedSegmentIds = outcome.unreviewedSegmentIds,
    )
  }
  return ReviewTreeAccounting.summarize(
    reviewId = specialists.first().reviewId,
    packetDigest = specialists.first().packetDigest,
    root = ReviewAccountingInput("parallel-review", sha256HexUtf8("parallel-review"), children = roots),
  )
}

internal fun ReviewAccountingSummary.withCommitFocusedAccounting(
  packet: ReviewContextPacket?,
  budget: ReviewContextBudgetPolicy,
  integration: ReviewIntegrationPassOutcome,
  coverage: ReviewCoverageReport?,
): ReviewAccountingSummary {
  if (packet == null) return this
  val matrix = packet.routingMatrix
  val focusedCommits = matrix.decisions.filter { it.focused }.map { it.commitSha }.distinct()
  return copy(
    commitRouting = ReviewCommitRoutingAccounting(
      commitSequenceDigest = packet.commitSequenceDigest,
      routingDigest = matrix.routingDigest,
      commitCount = matrix.commitShas.size,
      laneCount = matrix.lanes.size,
      focusedCommitCount = focusedCommits.size,
      skippedCommitCount = matrix.commitShas.size - focusedCommits.size,
      focusedPairCount = matrix.focusedPairCount,
      skippedPairCount = matrix.analyzedPairCount - matrix.focusedPairCount,
      incompleteLanes = coverage?.incompleteLanes?.map { it.lane }?.sorted().orEmpty(),
    ),
    parentAnalysis = ReviewParentAnalysisConsumption(
      analyzedPairs = matrix.analyzedPairCount,
      analyzedBytes = matrix.canonical.toByteArray(Charsets.UTF_8).size.toLong(),
      maxAnalysisPairs = budget.maxRoutingAnalysisPairs,
      maxAnalysisBytes = budget.maxRoutingAnalysisBytes,
    ),
    integration = ReviewIntegrationAccounting(
      commitSequenceDigest = integration.commitSequenceDigest,
      terminalOutcome = integration.terminalOutcome.wireValue,
      summarizedLaneCount = integration.summarizedLaneCount,
      findingCount = integration.findings.size,
      counters = ReviewAccountingCounters(
        launchBytes = integration.launchBytes,
        resultBytes = integration.resultBytes,
        modelTurns = integration.modelTurns,
      ),
      skipReason = integration.skipReason?.takeIf { it.isNotBlank() }
        ?: coverage?.integrationNotApplicableReason?.takeIf { it.isNotBlank() }
        ?: "the review compiled commit routing without recording why integration was not applicable"
          .takeIf {
            integration.terminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE
          },
    ),
  )
}

internal fun parallelReviewLaneTerminalOutcome(outcome: ParallelReviewLaneOutcome): String = when {
  outcome.reviewDisposition == ReviewLaneReviewDisposition.INCOMPLETE -> "incomplete"
  outcome.success -> "completed"
  else -> "partial_failure"
}
