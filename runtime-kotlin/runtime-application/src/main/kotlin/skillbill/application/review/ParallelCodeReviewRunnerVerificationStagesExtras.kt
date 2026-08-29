package skillbill.application.review

import skillbill.application.review.model.ReviewClaimVerificationOutcome
import skillbill.application.review.model.ReviewSpecAdjudicationOutcome
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.time.Instant

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
        recordedAt = Instant.now().toString(),
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
