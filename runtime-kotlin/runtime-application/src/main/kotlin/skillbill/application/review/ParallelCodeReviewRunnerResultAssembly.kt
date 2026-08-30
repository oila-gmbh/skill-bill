package skillbill.application.review

import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.model.ReviewIntegrationPassRunRequest
import skillbill.application.review.model.ReviewLaneIntegrationInput
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.review.ParallelReviewMerger
import skillbill.review.ReviewLaneAggregation
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.ReviewStageDegradationSelection
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewLaneAggregationInput
import skillbill.review.model.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationSelectionRequest
import skillbill.review.model.ReviewStageReached
import skillbill.review.model.ReviewStageResumeReport
import java.time.Instant

internal class ParallelCodeReviewRunnerResultAssembly(
  internal val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  internal val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  internal val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) {
  fun runIntegrationPass(
    initial: ParallelCodeReviewInitialRun,
    outcomes: ParallelReviewLaneRunResult,
  ): ReviewIntegrationPassOutcome {
    val packet = initial.compiledLaunchRequests.firstOrNull()?.packet
      ?: return ReviewIntegrationPassOutcome.skipped(
        PARALLEL_REVIEW_NO_SEQUENCE_DIGEST,
        "the review compiled no specialist lane, so there is no commit sequence to integrate over",
      )
    if (initial.resolvedMode == ResolvedReviewExecutionMode.INLINE) {
      return ReviewIntegrationPassOutcome.skipped(
        packet.commitSequenceDigest,
        "this review ran inline, so commit-focused delegated sequencing does not apply",
      )
    }
    durableIntegrationOutcome(initial.request.reviewRunId, packet.commitSequenceDigest)?.let { return it }
    val findingsByLane = outcomes.lane1.findings
      .groupingBy { it.specialistSkillName.orEmpty() }.eachCount()
    val lanes = initial.compiledLaunchRequests.map { launch ->
      ReviewLaneIntegrationInput(
        launch = launch,
        completion = parallelCodeReviewEffectiveCompletionState(launch, outcomes),
        findingCount = findingsByLane[launch.assignment.laneDecision.specialistSkillName] ?: 0,
      )
    }
    val outcome = ReviewIntegrationPassRunner(parentReviewLauncher, reviewContextEnvelopeValidator).run(
      ReviewIntegrationPassRunRequest(
        packet = packet,
        lanes = lanes,
        launch = initial.delegatedStageLaunch(),
      ),
    )
    recordIntegrationBoundary(initial.request.reviewRunId, outcome)
    return outcome
  }

  fun coverageReport(
    initial: ParallelCodeReviewInitialRun,
    outcomes: ParallelReviewLaneRunResult,
    integration: ReviewIntegrationPassOutcome,
  ): ReviewCoverageReport? {
    val packet = initial.compiledLaunchRequests.firstOrNull()?.packet ?: return null
    val ranThisPass = initial.preparedLaunchRequests.map { launch ->
      val completion = parallelCodeReviewEffectiveCompletionState(launch, outcomes)
      ReviewLaneAggregationInput(
        lane = launch.assignment.lane,
        commitSequenceDigest = packet.commitSequenceDigest,
        disposition = completion.disposition,
        unreviewedUnits = completion.unreviewedUnits,
      )
    }
    val results = ranThisPass + durablyCompleteLanes(initial, packet, ranThisPass)
    val bothAgentsSucceeded = outcomes.lane1.success
    return ReviewLaneAggregation.requireCompleteLaneResults(
      expectedLanes = packet.selectedLanes,
      results = results,
      commitSequenceDigest = packet.commitSequenceDigest,
    ).copy(
      integrationCompleted = integration.completed && bothAgentsSucceeded,
      integrationNotApplicableReason = integration.skipReason,
    )
  }

  fun recordLaneDispositions(initial: ParallelCodeReviewInitialRun, outcomes: ParallelReviewLaneRunResult) {
    val reviewRunId = initial.request.reviewRunId ?: return
    val existing = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordLaneDispositions.read",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (existing.isEmpty()) return
    val completionBySkill = initial.preparedLaunchRequests.associate { launch ->
      requireNotNull(launch.assignment.laneDecision.specialistSkillName) to
        parallelCodeReviewEffectiveCompletionState(launch, outcomes)
    }
    val updated = existing.map { lane ->
      val completion = completionBySkill[lane.laneSkillName] ?: return@map lane
      val durableComplete = completion.disposition == ReviewLaneReviewDisposition.COMPLETE
      lane.copy(
        reviewDisposition = if (durableComplete) {
          ReviewRunLaneResolver.COMPLETE_DISPOSITION
        } else {
          ReviewLaneReviewDisposition.INCOMPLETE.wireValue
        },
        bundleCompositionDigest = completion.bundleCompositionDigest,
        segmentAccountingJson = ReviewRunLaneSegmentAccountingJson.encode(completion.segments),
        unreviewedSegmentIds = completion.unreviewedSegmentIds,
        budgetDimension = completion.budgetDimension,
      )
    }
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordLaneDispositions.write",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, updated) }
  }

  fun recordMergedFindingLanes(reviewRunId: String?) {
    if (reviewRunId == null) return
    val claims = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordMergedFindingLanes.read",
      expected = "runtime-owned review pass claims",
    ) { unitOfWork ->
      unitOfWork.reviews.fetchReviewPassClaims(reviewRunId)
    }?.findings.orEmpty()
    val attribution = claims.mapNotNull { finding ->
      finding.specialistSkillNames.firstOrNull()?.let { finding.fNumber to it }
    }.toMap()
    if (attribution.isEmpty()) return
    runtimeOwnedPersistence.optionalWrite(
      seam = "ParallelCodeReviewRunner.recordMergedFindingLanes.write",
      expected = "optional runtime-owned finding lane attribution",
      fallback = Unit,
    ) { unitOfWork -> unitOfWork.reviews.recordFindingLaneAttribution(reviewRunId, attribution) }
  }

  fun emitReviewStageDegradations(reviewRunId: String?, outcomes: ParallelReviewLaneRunResult) {
    if (reviewRunId == null) return
    val evidenceBoundaries = evidenceBoundaryAccountings(outcomes)
    val selected = runtimeOwnedPersistence.optionalRead(
      seam = "ParallelCodeReviewRunner.emitReviewStageDegradations.read",
      expected = "optional review stage degradation inputs",
      fallback = emptyList(),
    ) { unitOfWork ->
      ReviewStageDegradationSelection.select(
        ReviewStageDegradationSelectionRequest(
          reviewRunId = reviewRunId,
          spec = unitOfWork.reviews.fetchSpecProjectionReference(reviewRunId),
          boundaries = unitOfWork.reviews.fetchStageBoundaries(reviewRunId),
          verdicts = unitOfWork.reviews.fetchFindingVerdicts(reviewRunId),
          claims = unitOfWork.reviews.fetchReviewPassClaims(reviewRunId),
          evidenceBoundaries = evidenceBoundaries,
        ),
      )
    }
    runtimeOwnedPersistence.optionalWrite(
      seam = "ParallelCodeReviewRunner.emitReviewStageDegradations.write",
      expected = "optional review stage degradation telemetry",
      fallback = Unit,
    ) { unitOfWork ->
      selected.forEach { unitOfWork.lifecycleTelemetry.reviewStageDegradation(it) }
    }
  }

  fun persistReviewPassClaims(
    reviewRunId: String?,
    findings: List<ParallelReviewMergedFinding>,
    persistEmpty: Boolean,
  ) {
    if (reviewRunId == null) return
    if (findings.isEmpty() && !persistEmpty) return
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.persistReviewPassClaims",
      expected = "runtime-owned review pass claims",
    ) { unitOfWork ->
      val recorded = unitOfWork.reviews.fetchReviewPassClaims(reviewRunId)
      val existing = recorded?.findings.orEmpty()
      val unioned = unionReviewPassClaims(existing, findings)
      if (unioned.isEmpty() && !persistEmpty) return@requiredWrite
      if (recorded != null && existing == unioned) return@requiredWrite
      unitOfWork.reviews.recordReviewPassClaims(reviewRunId, unioned)
    }
  }

  fun recordReviewStageBoundary(
    reviewRunId: String?,
    integration: ReviewIntegrationPassOutcome,
    findings: List<ParallelReviewMergedFinding>,
  ) {
    if (reviewRunId == null || !integration.durable) return
    val lanes = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordReviewStageBoundary.read",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (lanes.isEmpty() || lanes.any { it.reviewDisposition != ReviewRunLaneResolver.COMPLETE_DISPOSITION }) {
      return
    }
    persistReviewPassClaims(reviewRunId, findings, persistEmpty = true)
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordReviewStageBoundary.write",
      expected = "runtime-owned review stage boundary",
    ) { unitOfWork ->
      unitOfWork.reviews.recordStageBoundary(
        reviewRunId,
        ReviewStageBoundary(
          stage = ReviewStage.REVIEW,
          reached = ReviewStageReached.REACHED,
          recordedAt = Instant.now().toString(),
          contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        ),
      )
    }
  }

  fun stageResumeReport(reviewRunId: String?): ReviewStageResumeReport? {
    if (reviewRunId == null) return null
    return runtimeOwnedPersistence.optionalRead(
      seam = "ParallelCodeReviewRunner.stageResumeReport",
      expected = "optional runtime-owned review resume report",
      fallback = null,
    ) { unitOfWork ->
      ReviewStageResumeSelection.select(
        unitOfWork.reviews.fetchStageBoundaries(reviewRunId),
        unitOfWork.reviews.fetchFindingVerdicts(reviewRunId),
      )
    }
  }

  fun parallelResult(args: ParallelResultArgs): ParallelCodeReviewResult {
    val prose = args.outcomes.lane1.rawOutput.ifBlank { "Review completed with no prose body." }
    val lane1Result = ParallelReviewLaneResult(
      agentId = args.agent1Id,
      findings = args.outcomes.lane1.findings,
    )
    val integrationLane = args.integration.findings.takeIf { it.isNotEmpty() }?.let {
      ParallelReviewLaneResult(ReviewIntegrationPassRunner.INTEGRATION_LANE, it)
    }
    val merged = if (integrationLane != null) {
      ParallelReviewMerger.merge(lane1Result, integrationLane)
    } else {
      ParallelReviewMerger.merge(
        lane1Result,
        ParallelReviewLaneResult(agentId = args.agent1Id, findings = emptyList()),
      )
    }
    return ParallelCodeReviewResult(
      mergeResult = merged.copy(formattedOutput = prose),
      lane1 = args.outcomes.lane1.toParallelReviewLaneStatus(args.agent1Id),
      accountingSummary = parallelAccountingSummary(args.outcomes)
        ?.withCommitFocusedAccounting(args.packet, args.budget, args.integration, args.coverage),
      integration = args.integration,
      coverage = args.coverage,
      stageResume = args.stageResume,
    )
  }
}

private fun ParallelReviewLaneOutcome.toParallelReviewLaneStatus(agentId: String) = ParallelReviewLaneStatus(
  agentId,
  success,
  failureReason,
  droppedCandidateDiagnostic,
  budgetOutcome,
  accounting,
  specialistAccounting,
)
