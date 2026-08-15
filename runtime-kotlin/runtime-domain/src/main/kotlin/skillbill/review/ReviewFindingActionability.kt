package skillbill.review

import skillbill.review.model.RecordedVerdictFields
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingRegisterOutcome
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewStage

object ReviewFindingActionability {
  fun isActionable(claimVerdict: ReviewClaimVerdict?, scopeDisposition: ReviewScopeDisposition? = null): Boolean {
    if (claimVerdict == null) return true
    if (claimVerdict != ReviewClaimVerdict.CONFIRMED) return false
    return scopeDisposition == null ||
      scopeDisposition == ReviewScopeDisposition.IN_SCOPE ||
      scopeDisposition == ReviewScopeDisposition.SPEC_DEVIATION
  }

  fun overlayOf(
    findingRef: String?,
    recordedVerdicts: List<ReviewFindingVerdict>,
    encoded: RecordedVerdictFields,
  ): RecordedVerdictFields {
    val recorded = recordedFields(recordedVerdicts.filter { it.findingRef == findingRef })
    return RecordedVerdictFields(
      claimVerdict = recorded?.claimVerdict ?: encoded.claimVerdict,
      scopeDisposition = recorded?.scopeDisposition ?: encoded.scopeDisposition,
      citations = recorded?.citations?.takeIf { it.isNotEmpty() } ?: encoded.citations,
      severityAdjustment = recorded?.severityAdjustment ?: encoded.severityAdjustment,
    )
  }

  fun recordedFields(recorded: List<ReviewFindingVerdict>): RecordedVerdictFields? {
    if (recorded.isEmpty()) return null
    val verification = recorded.firstOrNull { it.stage == ReviewStage.VERIFICATION }
    val adjudication = recorded.firstOrNull { it.stage == ReviewStage.ADJUDICATION }
    return RecordedVerdictFields(
      claimVerdict = verification?.claimVerdict ?: adjudication?.claimVerdict,
      scopeDisposition = adjudication?.scopeDisposition,
      citations = adjudication?.citations?.takeIf { it.isNotEmpty() } ?: verification?.citations.orEmpty(),
      severityAdjustment = adjudication?.severityAdjustment ?: verification?.severityAdjustment,
    )
  }

  fun verificationVerdict(recorded: List<ReviewFindingVerdict>): ReviewFindingVerdict? =
    recorded.firstOrNull { it.stage == ReviewStage.VERIFICATION }

  fun conservativeClaimVerdict(left: ReviewClaimVerdict?, right: ReviewClaimVerdict?): ReviewClaimVerdict? =
    listOfNotNull(left, right).minByOrNull(::claimVerdictRank)

  fun conservativeScopeDisposition(
    left: ReviewScopeDisposition?,
    right: ReviewScopeDisposition?,
  ): ReviewScopeDisposition? = listOfNotNull(left, right).minByOrNull(::scopeDispositionRank)

  fun registerOutcome(
    claimVerdict: ReviewClaimVerdict?,
    scopeDisposition: ReviewScopeDisposition?,
  ): ReviewFindingRegisterOutcome = when {
    claimVerdict == ReviewClaimVerdict.REFUTED -> ReviewFindingRegisterOutcome.REFUTED
    claimVerdict == ReviewClaimVerdict.UNRESOLVED -> ReviewFindingRegisterOutcome.UNRESOLVED
    scopeDisposition == ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING ||
      scopeDisposition == ReviewScopeDisposition.SPEC_ACCEPTED_TRADEOFF ->
      ReviewFindingRegisterOutcome.OUT_OF_SCOPE
    else -> ReviewFindingRegisterOutcome.ACTIONABLE
  }

  private fun claimVerdictRank(verdict: ReviewClaimVerdict): Int = when (verdict) {
    ReviewClaimVerdict.UNRESOLVED -> 0
    ReviewClaimVerdict.CONFIRMED -> 1
    ReviewClaimVerdict.REFUTED -> 2
  }

  private fun scopeDispositionRank(disposition: ReviewScopeDisposition): Int = when (disposition) {
    ReviewScopeDisposition.IN_SCOPE, ReviewScopeDisposition.SPEC_DEVIATION -> 0
    ReviewScopeDisposition.SPEC_ACCEPTED_TRADEOFF, ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING -> 1
  }
}
