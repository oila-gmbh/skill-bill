package skillbill.review

import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.review.model.ReviewStageDegradationSelectionRequest
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

  fun select(request: ReviewStageDegradationSelectionRequest): List<ReviewStageDegradationMeasurement> {
    val byStage = request.boundaries.associateBy { it.stage }
    val specNone = request.spec?.absenceReason != null
    return buildList {
      specAbsence(request.reviewRunId, request.spec)?.let(::add)
      if (adjudicationSkipped(specNone, byStage)) {
        add(adjudicationSkip(request.reviewRunId, specNone))
      }
      workerFailure(request.reviewRunId, request.verdicts)?.let(::add)
      addAll(unreachedBoundaries(request.reviewRunId, specNone, byStage, request.claims))
      request.evidenceBoundaries.forEach { addAll(evidenceBoundaryRecords(request.reviewRunId, it)) }
    }
  }

  private fun specAbsence(
    reviewRunId: String,
    spec: ReviewSpecProjectionReference?,
  ): ReviewStageDegradationMeasurement? {
    val absenceReason = spec?.absenceReason ?: return null
    return ReviewStageDegradationMeasurement(
      reviewRunId = reviewRunId,
      seam = "review.spec_intent",
      expected = "resolved",
      actual = absenceReason,
      reason = ReviewStageDegradationReason.SPEC_CONTEXT_NONE,
    )
  }

  private fun adjudicationSkipped(specNone: Boolean, byStage: Map<ReviewStage, ReviewStageBoundary>): Boolean {
    val verificationReached = byStage[ReviewStage.VERIFICATION]?.reached == ReviewStageReached.REACHED
    val adjudicationReached = byStage[ReviewStage.ADJUDICATION]?.reached == ReviewStageReached.REACHED
    return specNone || (!verificationReached && !adjudicationReached)
  }

  private fun adjudicationSkip(reviewRunId: String, specNone: Boolean): ReviewStageDegradationMeasurement =
    ReviewStageDegradationMeasurement(
      reviewRunId = reviewRunId,
      seam = "review.adjudication",
      expected = "reached",
      actual = if (specNone) "skipped_spec_context_none" else "skipped",
      reason = ReviewStageDegradationReason.ADJUDICATION_SKIPPED,
    )

  private fun workerFailure(
    reviewRunId: String,
    verdicts: List<ReviewFindingVerdict>,
  ): ReviewStageDegradationMeasurement? {
    val failedWorker = verdicts.firstOrNull { verdict ->
      val reason = verdict.rejectionReason ?: return@firstOrNull false
      reason in workerLaunchOrReturnReasons ||
        reason.startsWith("agent exited with status ") ||
        reason.startsWith("unsupported agent:")
    } ?: return null
    return ReviewStageDegradationMeasurement(
      reviewRunId = reviewRunId,
      seam = "review.${failedWorker.stage.wireValue}.worker",
      expected = "worker_returned",
      actual = "launch_or_return_failed",
      reason = ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED,
    )
  }

  private fun unreachedBoundaries(
    reviewRunId: String,
    specNone: Boolean,
    byStage: Map<ReviewStage, ReviewStageBoundary>,
    claims: ReviewPassClaimSnapshot?,
  ): List<ReviewStageDegradationMeasurement> {
    val verificationReached = byStage[ReviewStage.VERIFICATION]?.reached == ReviewStageReached.REACHED
    return ReviewStage.entries.mapNotNull { stage ->
      val boundary = byStage[stage]
      val unreached = boundary?.reached == ReviewStageReached.NOT_REACHED ||
        missingVerificationBoundary(stage, claims, boundary) ||
        missingAdjudicationBoundary(stage, verificationReached, specNone, boundary)
      if (!unreached) {
        null
      } else {
        ReviewStageDegradationMeasurement(
          reviewRunId = reviewRunId,
          seam = "review.${stage.wireValue}.boundary",
          expected = "reached",
          actual = boundary?.reached?.wireValue ?: "absent",
          reason = ReviewStageDegradationReason.STAGE_BOUNDARY_UNREACHED,
        )
      }
    }
  }

  private fun missingVerificationBoundary(
    stage: ReviewStage,
    claims: ReviewPassClaimSnapshot?,
    boundary: ReviewStageBoundary?,
  ): Boolean = stage == ReviewStage.VERIFICATION && !claims?.findings.isNullOrEmpty() && boundary == null

  private fun missingAdjudicationBoundary(
    stage: ReviewStage,
    verificationReached: Boolean,
    specNone: Boolean,
    boundary: ReviewStageBoundary?,
  ): Boolean = stage == ReviewStage.ADJUDICATION && verificationReached && !specNone && boundary == null

  private fun evidenceBoundaryRecords(
    reviewRunId: String,
    accounting: ReviewEvidenceBoundaryAccounting,
  ): List<ReviewStageDegradationMeasurement> = buildList {
    evidenceBoundaryUnboundRecord(reviewRunId, accounting)?.let(::add)
    evidenceBoundaryUnexercisedRecord(reviewRunId, accounting)?.let(::add)
    evidenceBoundaryRefusedRecord(reviewRunId, accounting)?.let(::add)
    evidenceBoundaryRejectedRecord(reviewRunId, accounting)?.let(::add)
  }
}
