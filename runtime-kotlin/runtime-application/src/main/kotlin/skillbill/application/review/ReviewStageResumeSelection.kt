package skillbill.application.review

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import skillbill.review.model.ReviewStageResumeDegradation
import skillbill.review.model.ReviewStageResumeReport

object ReviewStageResumeSelection {
  const val SEAM: String = "ReviewStageResumeSelection.select"

  fun select(
    boundaries: List<ReviewStageBoundary>,
    verdicts: List<ReviewFindingVerdict>,
  ): ReviewStageResumeReport {
    val degradations = mutableListOf<ReviewStageResumeDegradation>()
    val durableByStage = ReviewStage.entries.associateWith { stage ->
      val stageBoundaries = boundaries.filter { it.stage == stage }
      val stageVerdicts = verdicts.filter { it.stage == stage }
      stageBoundaries.filter { it.contractVersion != REVIEW_CONTEXT_CONTRACT_VERSION }.forEach { drifted ->
        degradations += degradation(drifted.contractVersion, "boundary", stage)
      }
      stageVerdicts.filter { it.contractVersion != REVIEW_CONTEXT_CONTRACT_VERSION }.forEach { drifted ->
        degradations += degradation(drifted.contractVersion, "verdict", stage)
      }
      stageBoundaries.any {
        it.contractVersion == REVIEW_CONTEXT_CONTRACT_VERSION && it.reached == ReviewStageReached.REACHED
      }
    }
    return ReviewStageResumeReport(
      durableByStage = durableByStage,
      reentryStage = ReviewStage.entries.firstOrNull { durableByStage[it] != true },
      degradations = degradations,
    )
  }

  private fun degradation(used: String, kind: String, stage: ReviewStage) = ReviewStageResumeDegradation(
    seam = SEAM,
    used = used,
    expected = REVIEW_CONTEXT_CONTRACT_VERSION,
    cause = "Ignored $kind for ${stage.wireValue}; recorded under different contract semantics",
  )
}
