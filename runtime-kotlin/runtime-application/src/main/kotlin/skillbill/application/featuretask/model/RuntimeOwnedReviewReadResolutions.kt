package skillbill.application.featuretask.model

import skillbill.application.goalrunner.GoalSubtaskReviewImport
import skillbill.ports.persistence.UnitOfWork
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot

internal sealed interface RuntimeOwnedReviewImportResolution {
  data class ResolvedReviewImport(val review: GoalSubtaskReviewImport) : RuntimeOwnedReviewImportResolution

  data class Absent(val cause: String) : RuntimeOwnedReviewImportResolution

  data class ReadError(val cause: String) : RuntimeOwnedReviewImportResolution
}

internal sealed interface RuntimeOwnedReviewPassClaimsReadResolution {
  data class ResolvedPassClaims(val snapshot: ReviewPassClaimSnapshot) : RuntimeOwnedReviewPassClaimsReadResolution
  data class Absent(val cause: String) : RuntimeOwnedReviewPassClaimsReadResolution
  data class ReadError(val cause: String) : RuntimeOwnedReviewPassClaimsReadResolution
}

internal sealed interface RuntimeOwnedFindingVerdictsReadResolution {
  data class ResolvedFindingVerdicts(val verdicts: List<ReviewFindingVerdict>) :
    RuntimeOwnedFindingVerdictsReadResolution
  data class ReadError(val cause: String) : RuntimeOwnedFindingVerdictsReadResolution
}

internal class RuntimeOwnedFactUnavailable(message: String) : IllegalStateException(message)

internal fun UnitOfWork.resolveRuntimeOwnedReviewPassClaims(
  runId: String,
): RuntimeOwnedReviewPassClaimsReadResolution = try {
  reviews.fetchReviewPassClaims(runId)?.let(RuntimeOwnedReviewPassClaimsReadResolution::ResolvedPassClaims)
    ?: RuntimeOwnedReviewPassClaimsReadResolution.Absent("review pass claims are absent")
} catch (error: Exception) {
  RuntimeOwnedReviewPassClaimsReadResolution.ReadError(
    error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty(),
  )
}

internal fun UnitOfWork.resolveRuntimeOwnedFindingVerdicts(runId: String): RuntimeOwnedFindingVerdictsReadResolution =
  try {
    RuntimeOwnedFindingVerdictsReadResolution.ResolvedFindingVerdicts(reviews.fetchFindingVerdicts(runId))
  } catch (error: Exception) {
    RuntimeOwnedFindingVerdictsReadResolution.ReadError(
      error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty(),
    )
  }
