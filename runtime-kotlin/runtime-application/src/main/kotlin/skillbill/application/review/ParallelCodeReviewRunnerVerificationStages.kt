package skillbill.application.review

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ReviewClaimVerificationOutcome
import skillbill.application.review.model.ReviewClaimVerificationRunRequest
import skillbill.application.review.model.ReviewSpecAdjudicationOutcome
import skillbill.application.review.model.ReviewSpecAdjudicationRunRequest
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.time.Clock

class ParallelCodeReviewRunnerVerificationStages(
  val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  val clock: Clock,
) {
  fun recordedFindingVerdicts(reviewRunId: String?, inMemory: List<ReviewFindingVerdict>): List<ReviewFindingVerdict> {
    if (reviewRunId == null) return inMemory
    return runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordedFindingVerdicts",
      expected = "runtime-owned finding verdicts",
    ) { unitOfWork -> unitOfWork.reviews.fetchFindingVerdicts(reviewRunId) }
  }

  internal fun runClaimVerification(
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
    val outcome = ReviewClaimVerificationRunner(parentReviewLauncher, reviewContextEnvelopeValidator, clock).run(
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

  internal fun runSpecAdjudication(
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
    val outcome = ReviewSpecAdjudicationRunner(parentReviewLauncher, reviewContextEnvelopeValidator, clock).run(
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
          recordedAt = clock.instant().toString(),
          contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        ),
      )
    }
  }
}

internal fun ParallelCodeReviewRunnerVerificationStages.reviewStageBoundaries(
  reviewRunId: String?,
): List<ReviewStageBoundary> = if (reviewRunId == null) {
  emptyList()
} else {
  runtimeOwnedPersistence.requiredRead(
    seam = "ParallelCodeReviewRunner.reviewStageBoundaries",
    expected = "runtime-owned review stage boundaries",
  ) { unitOfWork -> unitOfWork.reviews.fetchStageBoundaries(reviewRunId) }
}

internal fun ParallelCodeReviewRunnerVerificationStages.claimVerificationClaims(
  reviewRunId: String?,
  boundaries: List<ReviewStageBoundary>,
  mergedFindings: List<ParallelReviewMergedFinding>,
): List<ParallelReviewMergedFinding> = if (reviewRunId == null) {
  mergedFindings
} else {
  val reviewReached = boundaries.any {
    it.stage == ReviewStage.REVIEW && it.reached == ReviewStageReached.REACHED
  }
  if (!reviewReached) {
    emptyList()
  } else {
    runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.claimVerificationClaims",
      expected = "runtime-owned review pass claims",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewPassClaims(reviewRunId) }
      ?.findings
      .orEmpty()
  }
}

internal fun ParallelCodeReviewRunnerVerificationStages.reviewFindingVerdicts(
  reviewRunId: String?,
): List<ReviewFindingVerdict> = if (reviewRunId == null) {
  emptyList()
} else {
  runtimeOwnedPersistence.requiredRead(
    seam = "ParallelCodeReviewRunner.reviewFindingVerdicts",
    expected = "runtime-owned finding verdicts",
  ) { unitOfWork -> unitOfWork.reviews.fetchFindingVerdicts(reviewRunId) }
}

internal fun ParallelCodeReviewRunnerVerificationStages.emptyClaimsVerificationShortCircuit(
  reviewRunId: String?,
  boundaries: List<ReviewStageBoundary>,
  verificationInput: String,
  existing: List<ReviewFindingVerdict>,
): List<ReviewFindingVerdict>? {
  if (
    reviewRunId != null &&
    boundaries.any { it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED }
  ) {
    return existing
  }
  if (!reviewOutputNeedsProseVerification(verificationInput)) {
    if (reviewRunId != null) recordVerificationBoundary(reviewRunId)
    return existing
  }
  return null
}

internal fun ParallelCodeReviewRunnerVerificationStages.persistClaimVerificationOutcome(
  reviewRunId: String?,
  claims: List<ParallelReviewMergedFinding>,
  existing: List<ReviewFindingVerdict>,
  outcome: ReviewClaimVerificationOutcome,
): List<ReviewFindingVerdict> {
  if (reviewRunId == null) return existing + outcome.verdicts
  if (outcome.verdicts.isNotEmpty()) {
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.persistClaimVerificationOutcome",
      expected = "runtime-owned finding verification verdicts",
    ) { unitOfWork ->
      unitOfWork.reviews.recordFindingVerdicts(reviewRunId, outcome.verdicts)
    }
  }
  val recordedRefs = (existing + outcome.verdicts)
    .filter { it.stage == ReviewStage.VERIFICATION }
    .map { it.findingRef }
    .toSet()
  if (claims.isNotEmpty() && claims.all { it.fNumber in recordedRefs }) {
    recordVerificationBoundary(reviewRunId)
  } else if (claims.isEmpty() && outcome.skipReason == null) {
    recordVerificationBoundary(reviewRunId)
  }
  return existing + outcome.verdicts
}

internal fun ParallelCodeReviewRunnerVerificationStages.recordVerificationBoundary(reviewRunId: String) {
  runtimeOwnedPersistence.requiredWrite(
    seam = "ParallelCodeReviewRunner.recordVerificationBoundary",
    expected = "runtime-owned verification stage boundary",
  ) { unitOfWork ->
    unitOfWork.reviews.recordStageBoundary(
      reviewRunId,
      ReviewStageBoundary(
        stage = ReviewStage.VERIFICATION,
        reached = ReviewStageReached.REACHED,
        recordedAt = clock.instant().toString(),
        contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
      ),
    )
  }
}

internal fun ParallelCodeReviewRunnerVerificationStages.durableAdjudication(
  reviewRunId: String?,
): List<ReviewFindingVerdict>? {
  if (reviewRunId == null) return null
  val boundaries = runtimeOwnedPersistence.requiredRead(
    seam = "ParallelCodeReviewRunner.durableAdjudication",
    expected = "runtime-owned adjudication stage boundaries",
  ) { unitOfWork ->
    unitOfWork.reviews.fetchStageBoundaries(reviewRunId)
  }
  val verificationReached = boundaries.any {
    it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED
  }
  if (!verificationReached) return emptyList()
  val adjudicationReached = boundaries.any {
    it.stage == ReviewStage.ADJUDICATION && it.reached == ReviewStageReached.REACHED
  }
  if (!adjudicationReached) return null
  return runtimeOwnedPersistence.requiredRead(
    seam = "ParallelCodeReviewRunner.durableAdjudication.verdicts",
    expected = "runtime-owned finding verdicts",
  ) { unitOfWork -> unitOfWork.reviews.fetchFindingVerdicts(reviewRunId) }
}

internal fun ParallelCodeReviewRunnerVerificationStages.persistAdjudication(
  reviewRunId: String?,
  outcome: ReviewSpecAdjudicationOutcome,
): List<ReviewFindingVerdict> {
  if (reviewRunId == null) return outcome.verdicts
  if (outcome.skipReason == ReviewSpecAdjudicationRunner.SPEC_CONTEXT_NONE) return emptyList()
  if (outcome.verdicts.isNotEmpty()) {
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.persistAdjudication",
      expected = "runtime-owned adjudication verdicts",
    ) { unitOfWork ->
      unitOfWork.reviews.recordFindingVerdicts(reviewRunId, outcome.verdicts)
    }
  }
  recordAdjudicationBoundary(reviewRunId)
  return outcome.verdicts
}
