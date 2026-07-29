package skillbill.application.featuretask

import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_MAX_PASSES
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDispositionRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGeneration
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGenerationIdentity
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState

internal data class GoalSubtaskReviewGenerationSettlementRequest(
  val workflowId: String,
  val state: GoalSubtaskReviewState,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  val repositoryCheckpoint: String?,
)

internal data class GoalSubtaskReviewGenerationSettlement(
  val state: GoalSubtaskReviewState,
  val passNumber: Int,
)

internal fun UnitOfWork.settleGoalSubtaskReviewGeneration(
  request: GoalSubtaskReviewGenerationSettlementRequest,
): GoalSubtaskReviewGenerationSettlement {
  require(
    request.state.completedPassCount == 0 ||
      reviewGenerations.summary(request.workflowId).currentGenerationId != null,
  ) {
    "Legacy completed review state has no durable review generation; regenerate or migrate it before review resumes."
  }
  val repositoryCheckpoint = requireNotNull(request.repositoryCheckpoint) {
    "Goal review completion requires the runtime repository checkpoint."
  }
  require(
    request.blockerDispositions.flatMap(GoalSubtaskBlockerDisposition::evidence)
      .all { it.startsWith("checkpoint=$repositoryCheckpoint;location=") },
  ) {
    "Every carried Blocker disposition must cite a location bound to the active repository checkpoint."
  }
  val completed = request.state.completeReservedPass(
    request.verdict,
    request.unresolvedFindingCount,
    request.findings,
    request.blockerDispositions,
  ).copy(reviewedRepositoryFingerprint = repositoryCheckpoint)
  val passNumber = completed.completedPassCount
  val reviewedDeltaDigest = requireNotNull(completed.activePassDeltaDigest) {
    "Goal review completion requires the exact active-pass delta digest."
  }
  val reviewedBase = request.state.remediationBaseSha
    ?.takeIf { request.state.reservedPassNumber == GOAL_SUBTASK_REVIEW_MAX_PASSES }
    ?: completed.reviewBaseSha
  val generationId = goalSubtaskReviewGenerationId(
    request.workflowId,
    reviewedBase,
    reviewedDeltaDigest,
    passNumber,
    repositoryCheckpoint,
  )
  appendGeneration(request, generationId, reviewedBase, reviewedDeltaDigest, repositoryCheckpoint)
  reviewGenerations.appendPass(request.workflowId, generationId, passNumber, repositoryCheckpoint)
  appendDispositions(request, generationId)
  appendFindings(request, generationId, passNumber)
  if (request.verdict == FeatureTaskRuntimeVerdict.APPROVED) {
    require(reviewGenerations.unresolvedBlockers(request.workflowId).isEmpty()) {
      "Review approval requires durable proof of zero unresolved Blockers across generations."
    }
  }
  return GoalSubtaskReviewGenerationSettlement(completed, passNumber)
}

private fun UnitOfWork.appendGeneration(
  request: GoalSubtaskReviewGenerationSettlementRequest,
  generationId: String,
  reviewedBase: String,
  reviewedDeltaDigest: String,
  repositoryCheckpoint: String,
) {
  reviewGenerations.appendGeneration(
    GoalSubtaskReviewGeneration(
      GoalSubtaskReviewGenerationIdentity(
        workflowId = request.workflowId,
        generationId = generationId,
        reviewBase = reviewedBase,
        reviewedDeltaDigest = reviewedDeltaDigest,
        repositoryCheckpoint = repositoryCheckpoint,
      ),
    ),
  )
}

private fun UnitOfWork.appendDispositions(
  request: GoalSubtaskReviewGenerationSettlementRequest,
  generationId: String,
) {
  val carried = reviewGenerations.unresolvedBlockers(request.workflowId)
  val durableDispositions = request.blockerDispositions.map { disposition ->
    val durableId = carried.singleOrNull {
      it.findingId == disposition.findingId || it.findingId.endsWith(":${disposition.findingId}")
    }?.findingId ?: disposition.findingId
    disposition.copy(findingId = durableId)
  }
  require(durableDispositions.map { it.findingId }.toSet() == carried.map { it.findingId }.toSet()) {
    "Review must disposition exactly the durable carried Blocker set."
  }
  durableDispositions.forEach { disposition ->
    reviewGenerations.appendDisposition(
      GoalSubtaskReviewFindingDispositionRecord(
        workflowId = request.workflowId,
        generationId = generationId,
        findingId = disposition.findingId,
        disposition = disposition.verdict.toGenerationDisposition(),
        evidence = disposition.evidence,
      ),
    )
  }
}

private fun UnitOfWork.appendFindings(
  request: GoalSubtaskReviewGenerationSettlementRequest,
  generationId: String,
  passNumber: Int,
) {
  request.findings.forEachIndexed { index, finding ->
    reviewGenerations.appendFinding(
      request.workflowId,
      generationId,
      passNumber,
      GoalSubtaskReviewFinding(
        findingId = finding.findingId ?: "finding-${index + 1}",
        severity = finding.severity,
        category = finding.category,
        location = finding.location,
        summary = finding.text,
        sourceGenerationId = generationId,
      ),
    )
  }
}

private fun GoalSubtaskBlockerDispositionVerdict.toGenerationDisposition(): GoalSubtaskReviewFindingDisposition =
  when (this) {
    GoalSubtaskBlockerDispositionVerdict.RESOLVED -> GoalSubtaskReviewFindingDisposition.RESOLVED
    GoalSubtaskBlockerDispositionVerdict.UNRESOLVED -> GoalSubtaskReviewFindingDisposition.STILL_PRESENT
    GoalSubtaskBlockerDispositionVerdict.SUPERSEDED -> GoalSubtaskReviewFindingDisposition.SUPERSEDED
  }
