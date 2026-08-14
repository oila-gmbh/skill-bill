package skillbill.review.context.model

import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage

data class ReviewClaimWorkerResult(
  val claimVerdict: String? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val findingRef: String? = null,
  val severity: String? = null,
  val location: String? = null,
  val description: String? = null,
)

data class ReviewClaimAdmissionResult(
  val claim: ParallelReviewMergedFinding,
  val verdict: ReviewFindingVerdict,
)

object ReviewClaimVerdictAdmission {
  const val UNCITED_REFUTATION: String = "refuted verdict requires a file:line citation"
  const val ALTERED_CLAIM: String = "worker result altered the finding claim"
  const val UNSETTLED: String = "worker did not settle the claim"

  fun admit(
    claim: ParallelReviewMergedFinding,
    worker: ReviewClaimWorkerResult?,
    recordedAt: String,
  ): ReviewClaimAdmissionResult {
    if (worker == null || claimAltered(claim, worker)) {
      return unresolved(claim, recordedAt, if (worker == null) UNSETTLED else ALTERED_CLAIM)
    }
    return when (worker.claimVerdict?.trim()?.lowercase()) {
      "confirmed" -> recorded(claim, recordedAt, ReviewClaimVerdict.CONFIRMED, worker.citations)
      "unresolved" -> recorded(claim, recordedAt, ReviewClaimVerdict.UNRESOLVED, worker.citations)
      "refuted" -> if (worker.citations.isEmpty()) {
        unresolved(claim, recordedAt, UNCITED_REFUTATION)
      } else {
        recorded(claim, recordedAt, ReviewClaimVerdict.REFUTED, worker.citations)
      }
      else -> unresolved(claim, recordedAt, UNSETTLED)
    }
  }

  private fun claimAltered(claim: ParallelReviewMergedFinding, worker: ReviewClaimWorkerResult): Boolean {
    if (worker.findingRef != null && worker.findingRef != claim.fNumber) return true
    if (worker.severity != null && worker.severity != claim.severity.displayName) return true
    if (worker.location != null && worker.location != claim.location) return true
    if (worker.description != null && worker.description != claim.description) return true
    return false
  }

  private fun unresolved(
    claim: ParallelReviewMergedFinding,
    recordedAt: String,
    reason: String,
  ): ReviewClaimAdmissionResult = ReviewClaimAdmissionResult(
    claim = claim,
    verdict = ReviewFindingVerdict(
      stage = ReviewStage.VERIFICATION,
      findingRef = claim.fNumber,
      claimVerdict = ReviewClaimVerdict.UNRESOLVED,
      recordedAt = recordedAt,
      rejectionReason = reason,
    ),
  )

  private fun recorded(
    claim: ParallelReviewMergedFinding,
    recordedAt: String,
    claimVerdict: ReviewClaimVerdict,
    citations: List<ReviewFindingCitation>,
  ): ReviewClaimAdmissionResult = ReviewClaimAdmissionResult(
    claim = claim,
    verdict = ReviewFindingVerdict(
      stage = ReviewStage.VERIFICATION,
      findingRef = claim.fNumber,
      claimVerdict = claimVerdict,
      citations = citations,
      recordedAt = recordedAt,
    ),
  )
}
