package skillbill.review.context.model

import skillbill.domain.review.context.model.SpecIntentProjection
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewStage

data class ReviewSpecAdjudicationWorkerResult(
  val scopeDisposition: String? = null,
  val dispositionValues: List<String> = emptyList(),
  val citedSpecElement: String? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustmentDirection: String? = null,
  val severityAdjustmentJustification: String? = null,
  val adjustedSeverity: String? = null,
  val findingRef: String? = null,
  val severity: String? = null,
  val location: String? = null,
  val description: String? = null,
)

object ReviewSpecAdjudicationAdmission {
  const val UNCITED_DOWNGRADE: String = "severity downgrade requires a cited spec element"
  const val UNCITED_RAISE: String = "severity raise requires a cited spec element"
  const val UNCITED_OUT_OF_SCOPE: String = "out_of_scope_preexisting requires a cited spec element"
  const val SPEC_DEVIATION_NOT_CONSTRAINT: String =
    "spec_deviation requires a cited constraint or non-goal present in the projection"
  const val UNKNOWN_SPEC_ELEMENT: String = "cited spec element is not present in the projection"
  const val AMBIGUOUS: String = "worker did not return exactly one scope disposition"
  const val ALTERED_CLAIM: String = "worker result altered the finding claim"
  const val UNSETTLED: String = "worker did not settle the disposition"

  fun admit(
    claim: ParallelReviewMergedFinding,
    stage1: ReviewFindingVerdict,
    projection: SpecIntentProjection,
    worker: ReviewSpecAdjudicationWorkerResult?,
    recordedAt: String,
  ): ReviewFindingVerdict {
    if (worker == null) return inScope(claim, stage1, recordedAt, UNSETTLED)
    if (claimAltered(claim, worker)) return inScope(claim, stage1, recordedAt, ALTERED_CLAIM)
    val dispositionWire = uniqueDisposition(worker) ?: return inScope(claim, stage1, recordedAt, AMBIGUOUS)
    val requested = runCatching { ReviewScopeDisposition.fromWire(dispositionWire) }.getOrNull()
      ?: return inScope(claim, stage1, recordedAt, AMBIGUOUS)
    val citedElement = worker.citedSpecElement?.trim()?.takeIf { it.isNotEmpty() }
    val projectionHit = citedElement?.let { element -> projection.namedElements().any { it == element } }
    val constraintHit = citedElement != null && (
      citedElement in projection.constraints || citedElement in projection.nonGoals
    )
    val requestedAdjustment = parsedAdjustment(worker)
    if (requested == ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING &&
      (worker.citations.isEmpty() || citedElement == null)
    ) {
      return inScope(claim, stage1, recordedAt, UNCITED_OUT_OF_SCOPE)
    }
    if (requested == ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING && projectionHit != true) {
      return inScope(claim, stage1, recordedAt, UNKNOWN_SPEC_ELEMENT)
    }
    if (requested == ReviewScopeDisposition.SPEC_DEVIATION && !constraintHit) {
      return inScope(claim, stage1, recordedAt, SPEC_DEVIATION_NOT_CONSTRAINT)
    }
    if (requestedAdjustment != null && worker.citations.isEmpty()) {
      val reason = if (requestedAdjustment.direction == ReviewSeverityAdjustmentDirection.LOWER) {
        UNCITED_DOWNGRADE
      } else {
        UNCITED_RAISE
      }
      return inScope(claim, stage1, recordedAt, reason)
    }
    if (requestedAdjustment != null && citedElement == null) {
      val reason = if (requestedAdjustment.direction == ReviewSeverityAdjustmentDirection.LOWER) {
        UNCITED_DOWNGRADE
      } else {
        UNCITED_RAISE
      }
      return inScope(claim, stage1, recordedAt, reason)
    }
    if (requestedAdjustment != null && projectionHit != true) {
      return inScope(claim, stage1, recordedAt, UNKNOWN_SPEC_ELEMENT)
    }
    return ReviewFindingVerdict(
      stage = ReviewStage.ADJUDICATION,
      findingRef = claim.fNumber,
      claimVerdict = stage1.claimVerdict,
      scopeDisposition = requested,
      citations = worker.citations,
      severityAdjustment = requestedAdjustment,
      recordedAt = recordedAt,
    )
  }

  private fun uniqueDisposition(worker: ReviewSpecAdjudicationWorkerResult): String? {
    val values = (listOfNotNull(worker.scopeDisposition?.trim()?.takeIf { it.isNotEmpty() }) + worker.dispositionValues)
      .map { it.trim().lowercase() }
      .distinct()
    return values.singleOrNull()
  }

  private fun parsedAdjustment(worker: ReviewSpecAdjudicationWorkerResult): ReviewSeverityAdjustment? {
    val direction = worker.severityAdjustmentDirection?.trim()?.lowercase()?.let { wire ->
      runCatching { ReviewSeverityAdjustmentDirection.fromWire(wire) }.getOrNull()
    } ?: return null
    val justification = listOfNotNull(
      worker.adjustedSeverity?.trim()?.takeIf { it.isNotEmpty() },
      worker.severityAdjustmentJustification?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(": ")
    if (justification.isBlank()) return null
    return ReviewSeverityAdjustment(direction, justification)
  }

  private fun claimAltered(
    claim: ParallelReviewMergedFinding,
    worker: ReviewSpecAdjudicationWorkerResult,
  ): Boolean {
    if (worker.findingRef != null && worker.findingRef != claim.fNumber) return true
    if (worker.severity != null && worker.severity != claim.severity.displayName) return true
    if (worker.location != null && worker.location != claim.location) return true
    if (worker.description != null && worker.description != claim.description) return true
    return false
  }

  private fun inScope(
    claim: ParallelReviewMergedFinding,
    stage1: ReviewFindingVerdict,
    recordedAt: String,
    reason: String,
  ): ReviewFindingVerdict = ReviewFindingVerdict(
    stage = ReviewStage.ADJUDICATION,
    findingRef = claim.fNumber,
    claimVerdict = stage1.claimVerdict,
    scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
    recordedAt = recordedAt,
    rejectionReason = reason,
  )
}

private fun SpecIntentProjection.namedElements(): List<String> =
  constraints + nonGoals + deferredItems
