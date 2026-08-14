package skillbill.review

import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.review.model.ReviewStageReached

object ReviewStageDegradationSelection {
  private val workerLaunchOrReturnReasons = setOf(
    "agent process failed to spawn",
    "agent timed out",
    "agent was interrupted",
    "agent exited with unknown status",
    "agent output exceeded the retention cap before completion",
    "verification launch exceeded max_lane_launch_bytes",
    "adjudication launch exceeded max_lane_launch_bytes",
    "unparseable verification output",
    "unparseable adjudication output",
  )

  fun select(
    reviewRunId: String,
    spec: ReviewSpecProjectionReference?,
    boundaries: List<ReviewStageBoundary>,
    verdicts: List<ReviewFindingVerdict>,
    claims: ReviewPassClaimSnapshot?,
  ): List<ReviewStageDegradationMeasurement> {
    val byStage = boundaries.associateBy { it.stage }
    val measurements = mutableListOf<ReviewStageDegradationMeasurement>()
    val specNone = spec?.absenceReason != null
    if (specNone) {
      measurements += ReviewStageDegradationMeasurement(
        reviewRunId = reviewRunId,
        seam = "review.spec_intent",
        expected = "resolved",
        actual = spec.absenceReason,
        reason = ReviewStageDegradationReason.SPEC_CONTEXT_NONE,
      )
    }
    val verificationReached = byStage[ReviewStage.VERIFICATION]?.reached == ReviewStageReached.REACHED
    val adjudicationReached = byStage[ReviewStage.ADJUDICATION]?.reached == ReviewStageReached.REACHED
    val adjudicationSkipped = specNone || (!verificationReached && !adjudicationReached)
    if (adjudicationSkipped) {
      measurements += ReviewStageDegradationMeasurement(
        reviewRunId = reviewRunId,
        seam = "review.adjudication",
        expected = "reached",
        actual = if (specNone) "skipped_spec_context_none" else "skipped",
        reason = ReviewStageDegradationReason.ADJUDICATION_SKIPPED,
      )
    }
    val failedWorker = verdicts.firstOrNull { verdict ->
      val reason = verdict.rejectionReason ?: return@firstOrNull false
      reason in workerLaunchOrReturnReasons ||
        reason.startsWith("agent exited with status ") ||
        reason.startsWith("unsupported agent:")
    }
    if (failedWorker != null) {
      measurements += ReviewStageDegradationMeasurement(
        reviewRunId = reviewRunId,
        seam = "review.${failedWorker.stage.wireValue}.worker",
        expected = "worker_returned",
        actual = "launch_or_return_failed",
        reason = ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED,
      )
    }
    ReviewStage.entries.forEach { stage ->
      val boundary = byStage[stage]
      val unreached = when {
        boundary?.reached == ReviewStageReached.NOT_REACHED -> true
        stage == ReviewStage.VERIFICATION &&
          !claims?.findings.isNullOrEmpty() &&
          boundary == null -> true
        stage == ReviewStage.ADJUDICATION &&
          verificationReached &&
          !specNone &&
          boundary == null -> true
        else -> false
      }
      if (unreached) {
        measurements += ReviewStageDegradationMeasurement(
          reviewRunId = reviewRunId,
          seam = "review.${stage.wireValue}.boundary",
          expected = "reached",
          actual = boundary?.reached?.wireValue ?: "absent",
          reason = ReviewStageDegradationReason.STAGE_BOUNDARY_UNREACHED,
        )
      }
    }
    return measurements
  }
}
