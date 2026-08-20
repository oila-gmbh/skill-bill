package skillbill.review

import skillbill.review.model.ReviewEvidenceBoundaryAccounting
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

  @Suppress("LongParameterList")
  fun select(
    reviewRunId: String,
    spec: ReviewSpecProjectionReference?,
    boundaries: List<ReviewStageBoundary>,
    verdicts: List<ReviewFindingVerdict>,
    claims: ReviewPassClaimSnapshot?,
    evidenceBoundaries: List<ReviewEvidenceBoundaryAccounting> = emptyList(),
  ): List<ReviewStageDegradationMeasurement> {
    val byStage = boundaries.associateBy { it.stage }
    val specNone = spec?.absenceReason != null
    return buildList {
      specAbsence(reviewRunId, spec)?.let(::add)
      if (adjudicationSkipped(specNone, byStage)) {
        add(adjudicationSkip(reviewRunId, specNone))
      }
      workerFailure(reviewRunId, verdicts)?.let(::add)
      addAll(unreachedBoundaries(reviewRunId, specNone, byStage, claims))
      evidenceBoundaries.forEach { addAll(evidenceBoundaryRecords(reviewRunId, it)) }
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
    accounting.unboundSeam?.let { seam ->
      add(
        ReviewStageDegradationMeasurement(
          reviewRunId = reviewRunId,
          seam = seam,
          expected = "bound",
          actual = "unbound",
          reason = ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER,
        ),
      )
    }
    if (
      accounting.unboundSeam == null &&
      accounting.governedLaunchCount > 0 &&
      accounting.authorizedReadCount == 0
    ) {
      add(
        ReviewStageDegradationMeasurement(
          reviewRunId = reviewRunId,
          seam = ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
          expected = "authorized_reads>0",
          actual = "authorized_reads=0",
          reason = ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED,
        ),
      )
    }
    if (accounting.refusedOperationCount > 0) {
      add(
        ReviewStageDegradationMeasurement(
          reviewRunId = reviewRunId,
          seam = ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
          expected = "refused_operations=0",
          actual = "refused_operations=${accounting.refusedOperationCount}" +
            accounting.refusedCategories
              .groupingBy { it }
              .eachCount()
              .entries
              .sortedBy { it.key }
              .joinToString(",", prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
              .takeIf { accounting.refusedCategories.isNotEmpty() }
              .orEmpty(),
          reason = ReviewStageDegradationReason.EVIDENCE_BOUNDARY_OPERATION_REFUSED,
        ),
      )
    }
    if (accounting.rejectedCandidateCount > 0) {
      add(
        ReviewStageDegradationMeasurement(
          reviewRunId = reviewRunId,
          seam = "review.register.parse",
          expected = "rejected_candidates=0",
          actual = "rejected_candidates=${accounting.rejectedCandidateCount}",
          reason = ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED,
        ),
      )
    }
  }
}
