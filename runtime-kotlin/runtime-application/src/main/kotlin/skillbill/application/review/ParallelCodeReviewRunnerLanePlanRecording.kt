package skillbill.application.review

import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.time.Clock

internal class ParallelCodeReviewRunnerLanePlanRecording(
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  private val clock: Clock,
) {
  fun recordSpecIntent(reviewRunId: String?, resolution: SpecIntentResolution) {
    if (reviewRunId == null) return
    val reference = when (resolution) {
      is SpecIntentResolution.Resolved -> ReviewSpecProjectionReference(
        specPath = resolution.projection.provenance.specPath,
        contentDigest = resolution.projection.provenance.contentDigest,
      )
      is SpecIntentResolution.None -> ReviewSpecProjectionReference(absenceReason = resolution.reason.wireValue)
    }
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordSpecIntent",
      expected = "runtime-owned review spec projection reference",
    ) { unitOfWork ->
      unitOfWork.reviews.recordSpecProjectionReference(reviewRunId, reference)
      if (resolution is SpecIntentResolution.None) {
        unitOfWork.reviews.recordStageBoundary(
          reviewRunId,
          ReviewStageBoundary(
            stage = ReviewStage.ADJUDICATION,
            reached = ReviewStageReached.NOT_REACHED,
            recordedAt = clock.instant().toString(),
            contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
          ),
        )
      }
    }
  }

  fun selectLaunchesForResume(
    reviewRunId: String?,
    launches: List<ReviewSpecialistLaunchRequest>,
  ): List<ReviewSpecialistLaunchRequest> {
    if (reviewRunId == null || launches.isEmpty()) return launches
    val existing = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.selectLaunchesForResume",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    if (existing.isEmpty()) return launches
    val completeNames = existing
      .filter { it.reviewDisposition == ReviewRunLaneResolver.COMPLETE_DISPOSITION }
      .map { it.laneSkillName }
      .toSet()
    return launches.filterNot { launch ->
      launch.assignment.laneDecision.specialistSkillName in completeNames
    }
  }

  fun recordPlannedLanes(
    reviewRunId: String?,
    plannedRubrics: List<PlannedReviewRubric>,
    launches: List<ReviewSpecialistLaunchRequest>,
  ) {
    if (reviewRunId == null || launches.isEmpty()) return
    val existing = runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordPlannedLanes.read",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
    val preservedComplete = existing.filter {
      it.reviewDisposition == ReviewRunLaneResolver.COMPLETE_DISPOSITION
    }
    val completionBySkill = launches.associate { launch ->
      requireNotNull(launch.assignment.laneDecision.specialistSkillName) to
        parallelCodeReviewGovernedLaunchFor(launch).completionState
    }
    val relaunchNames = completionBySkill.keys
    val pending = plannedRubrics
      .filter { it.descriptor.skillName in relaunchNames }
      .map { planned ->
        val completion = completionBySkill.getValue(planned.descriptor.skillName)
        ReviewRunLane(
          laneSkillName = planned.descriptor.skillName,
          packSlug = planned.descriptor.packSlug,
          area = planned.descriptor.area,
          depth = planned.descriptor.depth,
          required = planned.descriptor.required,
          orderIndex = planned.descriptor.orderIndex,
          originLayerChain = planned.descriptor.originLayerChain,
          resolutionState = ReviewRunLaneResolver.RESOLVED,
          reviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE.wireValue,
          bundleCompositionDigest = completion.bundleCompositionDigest,
          segmentAccountingJson = ReviewRunLaneSegmentAccountingJson.encode(completion.segments),
          unreviewedSegmentIds = completion.unreviewedSegmentIds,
          budgetDimension = completion.budgetDimension,
        )
      }
    val merged = preservedComplete.filter { it.laneSkillName !in relaunchNames } + pending
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordPlannedLanes.write",
      expected = "runtime-owned review lane plan",
    ) { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, merged) }
  }
}
