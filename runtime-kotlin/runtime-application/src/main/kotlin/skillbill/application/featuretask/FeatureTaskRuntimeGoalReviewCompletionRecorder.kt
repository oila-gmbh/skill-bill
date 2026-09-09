package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.GoalReviewPhaseCompletionRequest
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.application.subtaskreview.UnaddressedFindingLedgerScope
import skillbill.application.subtaskreview.recordedVerdicts
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewRevision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.goal.model.unionRefutedBlockerDispositions
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import java.time.Clock

class FeatureTaskRuntimeGoalReviewCompletionRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val clock: Clock,
) : FeatureTaskRuntimePhaseReviewApi {
  override fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean {
    val request = validatedGoalReviewPhaseState(completion)
    return database.transaction { unitOfWork ->
      persistCompletedGoalReview(unitOfWork, request, completion)
    }
  }

  private fun persistCompletedGoalReview(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    completion: GoalReviewPhaseCompletionRequest,
  ): Boolean {
    val write = goalReviewCompletionWrite(unitOfWork, request, completion) ?: return false
    persistUnaddressedFindings(
      unitOfWork,
      request,
      write.continuation,
      write.completedState.completedPassCount,
      write.dispositions,
    )
    workflowPersistence.persistPatch(
      unitOfWork.workflowStates,
      write.record,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to write.completedState.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to (
          write.persisted.rawResults +
            (write.completedState.completedPassCount.toString() to completion.rawReviewResult)
          ),
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          write.persisted.updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to
          goalReviewCompletionLedger(request, write.persisted.artifacts),
      ),
      WorkflowRowAdvance(
        currentStepId = request.phaseId,
        workflowStatus = workflowStatusFor(request),
        stepUpdates = stepUpdatesFrom(write.persisted.updatedRecords),
      ),
    )
    return true
  }

  private data class GoalReviewCompletionArtifacts(
    val artifacts: Map<String, Any?>,
    val rawResults: Map<String, String>,
    val updatedRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  )

  private data class GoalReviewCompletionWrite(
    val record: WorkflowStateSnapshot,
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    val completedState: GoalSubtaskReviewState,
    val dispositions: List<GoalSubtaskBlockerDisposition>,
    val persisted: GoalReviewCompletionArtifacts,
  )

  private fun goalReviewCompletionWrite(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    completion: GoalReviewPhaseCompletionRequest,
  ): GoalReviewCompletionWrite? {
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val reviewArtifacts = GoalSubtaskReviewArtifactDecoder.decode(artifacts) ?: return null
    val reservedPass = reviewArtifacts.state.reservedPassNumber ?: 1
    val envelope = goalReviewCompletionEnvelope(request)
    val recordedVerdicts = GoalSubtaskReviewSummaryReducer.recordedVerdicts(unitOfWork, envelope)
    val currentFindings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = envelope,
      scope = UnaddressedFindingLedgerScope(
        issueKey = reviewArtifacts.continuation.issueKey,
        subtaskId = reviewArtifacts.continuation.subtaskId,
        workflowId = request.workflowId,
        reviewPassNumber = reservedPass,
      ),
      recordedVerdicts = recordedVerdicts,
    )
    val dispositions = goalReviewCompletionDispositions(
      reservedPass,
      completion.blockerDispositions,
      unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId),
      currentFindings,
      recordedVerdicts,
    )
    val existingRecords = phaseRecordsFrom(artifacts)
    val completed = reviewArtifacts.state.completeReservedPass(
      verdict = when {
        completion.commitFocusedAccounting?.isCleanCoverage == false -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
        GoalSubtaskReviewSummaryReducer.evidenceCoverageComplete(envelope) == false ->
          FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
        else -> completion.verdict
      },
      unresolvedFindingCount = completion.unresolvedFindingCount,
      findings = completion.findings,
      blockerDispositions = dispositions,
      revision = GoalSubtaskReviewRevision(
        commitFocusedAccounting = completion.commitFocusedAccounting,
      ),
    )
    return GoalReviewCompletionWrite(
      record = record,
      continuation = reviewArtifacts.continuation,
      completedState = withReviewedIdentity(completed, artifacts),
      dispositions = dispositions,
      persisted = GoalReviewCompletionArtifacts(
        artifacts = artifacts,
        rawResults = reviewArtifacts.rawResults,
        updatedRecords = LinkedHashMap(existingRecords).apply {
          put(
            request.phaseId,
            featureTaskRuntimePhaseRecordFor(request, existingRecords[request.phaseId], clock.instant().toString()),
          )
        },
      ),
    )
  }

  private fun withReviewedIdentity(
    completed: GoalSubtaskReviewState,
    artifacts: Map<String, Any?>,
  ): GoalSubtaskReviewState {
    val reviewedInput = artifacts[GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY] as? Map<*, *>
    val reviewedTarget = reviewedInput?.get("current_head_sha") as? String
    val reviewedTree = reviewedInput?.get("reviewed_tree_sha") as? String
    return completed.copy(
      reviewedTargetSha = reviewedTarget?.takeIf(String::isNotBlank) ?: completed.reviewedTargetSha,
      reviewedTreeSha = reviewedTree?.takeIf(String::isNotBlank) ?: completed.reviewedTreeSha,
    )
  }

  private fun goalReviewCompletionEnvelope(request: FeatureTaskRuntimePhaseStateRequest) =
    requireNotNull(request.normalizedOutput) {
      "Goal review completion requires normalized output to persist the unaddressed-findings ledger."
    }.envelope

  private fun goalReviewCompletionDispositions(
    reservedPass: Int,
    requested: List<GoalSubtaskBlockerDisposition>,
    priorFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict>,
  ): List<GoalSubtaskBlockerDisposition> {
    if (reservedPass <= 1) return requested
    return unionRefutedBlockerDispositions(
      requested,
      GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(priorFindings, currentFindings, recordedVerdicts),
    )
  }

  private fun goalReviewCompletionLedger(
    request: FeatureTaskRuntimePhaseStateRequest,
    artifacts: Map<String, Any?>,
  ): List<Map<String, Any?>> {
    val ledger = phaseLedgerFrom(artifacts)
    val completionEntry = FeatureTaskRuntimePhaseLedgerEntry(
      action = COMPLETE,
      sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      timestamp = clock.instant().toString(),
      phaseId = request.phaseId,
      attemptCount = request.attemptCount,
      resolvedAgentId = request.resolvedAgentId,
      loopId = request.loopId,
      edgeIteration = request.edgeIteration,
    )
    return appendBoundedHistoryBySequence(
      ledger.map { it.toArtifactMap() },
      completionEntry.toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
    )
  }

  private fun persistUnaddressedFindings(
    unitOfWork: UnitOfWork,
    request: FeatureTaskRuntimePhaseStateRequest,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    passNumber: Int,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ) {
    val output = requireNotNull(request.normalizedOutput) {
      "Goal review completion requires normalized output to persist the unaddressed-findings ledger."
    }.envelope
    val recordedVerdicts = GoalSubtaskReviewSummaryReducer.recordedVerdicts(unitOfWork, output)
    val findings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = output,
      scope = UnaddressedFindingLedgerScope(
        issueKey = continuation.issueKey,
        subtaskId = continuation.subtaskId,
        workflowId = request.workflowId,
        reviewPassNumber = passNumber,
      ),
      recordedVerdicts = recordedVerdicts,
    )
    val superseded = unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId)
    unitOfWork.unaddressedFindings.replaceLedgerForPass(request.workflowId, passNumber, findings)
    unitOfWork.unaddressedFindings.recordOutcomes(
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = superseded,
        currentFindings = findings,
        blockerDispositions = blockerDispositions,
      ),
    )
  }

  private fun validatedGoalReviewPhaseState(
    completion: GoalReviewPhaseCompletionRequest,
  ): FeatureTaskRuntimePhaseStateRequest {
    val request = completion.phaseState
    require(request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      "Goal review completion can only persist the review phase."
    }
    require(request.status == "completed" && request.finished) {
      "Goal review completion must persist a finished completed review phase."
    }
    require(completion.rawReviewResult.isNotBlank()) { "Goal-subtask review pass result must be non-blank." }
    return request
  }
}
