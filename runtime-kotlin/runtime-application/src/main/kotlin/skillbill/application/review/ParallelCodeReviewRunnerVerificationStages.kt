package skillbill.application.review

import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ReviewClaimVerificationRunRequest
import skillbill.application.review.model.ReviewSpecAdjudicationRunRequest
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.time.Instant

internal class ParallelCodeReviewRunnerVerificationStages(
  internal val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  internal val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  internal val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) {
  fun recordedFindingVerdicts(reviewRunId: String?, inMemory: List<ReviewFindingVerdict>): List<ReviewFindingVerdict> {
    if (reviewRunId == null) return inMemory
    return runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordedFindingVerdicts",
      expected = "runtime-owned finding verdicts",
    ) { unitOfWork -> unitOfWork.reviews.fetchFindingVerdicts(reviewRunId) }
  }

  fun runClaimVerification(
    initial: ParallelCodeReviewInitialRun,
    result: ParallelCodeReviewResult,
  ): List<ReviewFindingVerdict> {
    val reviewRunId = initial.request.reviewRunId
    val boundaries = reviewStageBoundaries(reviewRunId)
    val claims = claimVerificationClaims(reviewRunId, boundaries, result.mergeResult.findings)
    val existing = reviewFindingVerdicts(reviewRunId)
    val verifiedRefs = existing
      .filter { it.stage == ReviewStage.VERIFICATION }
      .map { it.findingRef }
      .toSet()
    if (claims.isNotEmpty() && claims.all { it.fNumber in verifiedRefs }) {
      if (reviewRunId != null) recordVerificationBoundary(reviewRunId)
      return existing
    }
    val verificationInput = verificationReviewOutput(result.output, claims)
    if (claims.isEmpty()) {
      emptyClaimsVerificationShortCircuit(reviewRunId, boundaries, verificationInput, existing)?.let { return it }
    }
    val outcome = ReviewClaimVerificationRunner(parentReviewLauncher, reviewContextEnvelopeValidator).run(
      ReviewClaimVerificationRunRequest(
        packet = initial.compiledLaunchRequests.firstOrNull()?.packet,
        reviewOutput = verificationInput,
        findings = claims,
        existingVerdicts = existing,
        mode = initial.resolvedMode,
        launch = initial.delegatedStageLaunch(),
      ),
    )
    return persistClaimVerificationOutcome(reviewRunId, claims, existing, outcome)
  }

  fun runSpecAdjudication(
    initial: ParallelCodeReviewInitialRun,
    result: ParallelCodeReviewResult,
  ): List<ReviewFindingVerdict> {
    val reviewRunId = initial.request.reviewRunId
    durableAdjudication(reviewRunId)?.let { return it }
    val projection = (initial.specIntentResolution as? SpecIntentResolution.Resolved)?.projection
    val claims = if (reviewRunId == null) {
      result.mergeResult.findings
    } else {
      runtimeOwnedPersistence.requiredRead(
        seam = "ParallelCodeReviewRunner.runSpecAdjudication.claims",
        expected = "runtime-owned review pass claims",
      ) { unitOfWork -> unitOfWork.reviews.fetchReviewPassClaims(reviewRunId) }
        ?.findings
        .orEmpty()
    }
    val existing = if (reviewRunId == null) {
      emptyList()
    } else {
      runtimeOwnedPersistence.requiredRead(
        seam = "ParallelCodeReviewRunner.runSpecAdjudication.verdicts",
        expected = "runtime-owned finding verdicts",
      ) { unitOfWork -> unitOfWork.reviews.fetchFindingVerdicts(reviewRunId) }
    }
    val outcome = ReviewSpecAdjudicationRunner(parentReviewLauncher, reviewContextEnvelopeValidator).run(
      ReviewSpecAdjudicationRunRequest(
        packet = initial.compiledLaunchRequests.firstOrNull()?.packet,
        findings = claims,
        existingVerdicts = existing,
        projection = projection,
        launch = initial.delegatedStageLaunch(),
      ),
    )
    return persistAdjudication(reviewRunId, outcome)
  }

  fun recordAdjudicationBoundary(reviewRunId: String) {
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordAdjudicationBoundary",
      expected = "runtime-owned adjudication stage boundary",
    ) { unitOfWork ->
      unitOfWork.reviews.recordStageBoundary(
        reviewRunId,
        ReviewStageBoundary(
          stage = ReviewStage.ADJUDICATION,
          reached = ReviewStageReached.REACHED,
          recordedAt = Instant.now().toString(),
          contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        ),
      )
    }
  }
}
