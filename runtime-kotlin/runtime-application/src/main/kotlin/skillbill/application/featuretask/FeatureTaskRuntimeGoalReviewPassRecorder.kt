package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.goalrunner.recordedVerdicts
import skillbill.application.workflow.WorkflowFamily
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskReviewState

internal class FeatureTaskRuntimeGoalReviewPassRecorder(
  private val database: DatabaseSessionFactory,
  private val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) {
  fun reserveGoalReviewPass(workflowId: String, dbOverride: String? = null): GoalSubtaskReviewPassReservation =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction GoalSubtaskReviewPassReservation.MissingState
      val artifacts = decodeArtifacts(record.artifactsJson)
      val state = reviewStateFromArtifacts(artifacts)
        ?: return@transaction GoalSubtaskReviewPassReservation.MissingState
      if (state.reviewCapReached || state.reviewSkippedByUser) {
        return@transaction GoalSubtaskReviewPassCarryForward(state)
      }
      if (state.reservedPassNumber != null) {
        return@transaction GoalSubtaskReviewPassInFlight(state)
      }
      val reserved = state.reserveNextPass()
      if (reserved != state) {
        patcher.save(
          record,
          unitOfWork.workflowStates,
          mapOf(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to reserved.toArtifactMap()),
        )
      }
      GoalSubtaskReviewPassReserved(reserved)
    }

  fun persistGoalReviewInput(
    workflowId: String,
    input: GoalSubtaskReviewInput,
    dbOverride: String? = null,
  ): GoalSubtaskReviewState? = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val state = reviewStateFromArtifacts(artifacts)
      ?: return@transaction null
    check(input.reviewBaseSha == state.reviewBaseSha || input.reviewBaseSha == state.remediationBaseSha) {
      "Goal-subtask review input does not match the durable review baseline or its recorded remediation base."
    }
    val updated = state.copy(
      reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
      reviewedDeltaDigest = if (input.reviewBaseSha == state.reviewBaseSha) {
        input.deltaDigest
      } else {
        state.reviewedDeltaDigest
      },
    )
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to updated.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY to input.toArtifactMap(),
      ),
    )
    updated
  }

  fun updateReviewState(
    workflowId: String,
    dbOverride: String? = null,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val state = reviewStateFromArtifacts(decodeArtifacts(record.artifactsJson)) ?: return@transaction null
    val updated = transform(state)
    if (updated == state) return@transaction state
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to updated.toArtifactMap()),
    )
    updated
  }

  fun completeGoalReviewPass(
    request: GoalReviewPassCompletionRequest,
    dbOverride: String? = null,
  ): GoalSubtaskReviewState? = runtimeOwnedPersistence.requiredWrite(
    seam = "FeatureTaskRuntimeGoalContinuationRecorder.completeGoalReviewPass",
    expected = "runtime-owned review completion persistence",
    dbOverride = dbOverride,
  ) { unitOfWork ->
    val loaded = loadGoalReviewPassWrite(unitOfWork, request) ?: return@requiredWrite null
    val completed = loaded.state.completeReservedPass(
      request.verdict,
      request.unresolvedFindingCount,
      request.findings,
      loaded.dispositions,
      request.commitFocusedAccounting,
    )
    persistGoalReviewPassWrite(unitOfWork, loaded, request, completed)
    completed
  }

  fun lastGoalReviewResult(workflowId: String, dbOverride: String? = null): String? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val state = reviewStateFromArtifacts(artifacts)
        ?: return@read null
      val passNumber = state.passResults.lastOrNull()?.passNumber ?: return@read null
      rawReviewResultsFromArtifacts(artifacts, state)[passNumber.toString()]
    }

  private data class GoalReviewPassWrite(
    val record: WorkflowStateSnapshot,
    val state: GoalSubtaskReviewState,
    val previousResults: Map<String, String>,
    val ledgerFindings: List<UnaddressedFinding>,
    val supersededFindings: List<UnaddressedFinding>,
    val dispositions: List<GoalSubtaskBlockerDisposition>,
  )

  private fun loadGoalReviewPassWrite(
    unitOfWork: UnitOfWork,
    request: GoalReviewPassCompletionRequest,
  ): GoalReviewPassWrite? {
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val state = reviewStateFromArtifacts(artifacts) ?: return null
    require(request.rawReviewResult.isNotBlank()) { "Goal-subtask review pass result must be non-blank." }
    val continuation = continuationFromArtifacts(artifacts)
      ?: error("Goal-subtask review continuation is missing during reserved-pass recovery.")
    val reservedPass = state.reservedPassNumber ?: 1
    val recordedVerdicts = GoalSubtaskReviewSummaryReducer.recordedVerdicts(unitOfWork, request.normalizedOutput)
    val ledgerFindings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = request.normalizedOutput,
      scope = UnaddressedFindingLedgerScope(
        issueKey = continuation.issueKey,
        subtaskId = continuation.subtaskId,
        workflowId = request.workflowId,
        reviewPassNumber = reservedPass,
      ),
      recordedVerdicts = recordedVerdicts,
    )
    val supersededFindings = unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId)
    val dispositions = request.blockerDispositions
    return GoalReviewPassWrite(
      record = record,
      state = state,
      previousResults = rawReviewResultsFromArtifacts(artifacts, state),
      ledgerFindings = ledgerFindings,
      supersededFindings = supersededFindings,
      dispositions = dispositions,
    )
  }

  private fun persistGoalReviewPassWrite(
    unitOfWork: UnitOfWork,
    loaded: GoalReviewPassWrite,
    request: GoalReviewPassCompletionRequest,
    completed: GoalSubtaskReviewState,
  ) {
    val passNumber = completed.completedPassCount.toString()
    unitOfWork.unaddressedFindings.replaceLedgerForPass(request.workflowId, passNumber.toInt(), loaded.ledgerFindings)
    unitOfWork.unaddressedFindings.recordOutcomes(
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = loaded.supersededFindings,
        currentFindings = loaded.ledgerFindings,
        blockerDispositions = loaded.dispositions,
      ),
    )
    patcher.save(
      loaded.record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to completed.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to (loaded.previousResults + (passNumber to request.rawReviewResult)),
      ),
    )
  }
}
