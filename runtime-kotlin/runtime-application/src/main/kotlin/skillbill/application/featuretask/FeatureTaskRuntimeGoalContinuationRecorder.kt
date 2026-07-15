package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState

@Inject
class FeatureTaskRuntimeGoalContinuationRecorder(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  internal fun recordGoalContinuationState(
    request: GoalContinuationStateRecordRequest,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existingContinuation = continuationFromArtifacts(artifacts)
    check(
      existingContinuation == null ||
        request.continuation == null ||
        existingContinuation == request.continuation,
    ) {
      "Goal continuation is immutable for workflow '${request.workflowId}'; " +
        "parent, subtask, branch, and review mode cannot change on resume."
    }
    val continuationPatch = continuationPatch(request.continuation, existingContinuation)
    val reviewStatePatch = reviewStatePatch(request, artifacts, existingContinuation)
    val outcomePatch = request.outcome?.let {
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY to it.toArtifactMap())
    }.orEmpty()
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = request.workflowStatus ?: record.workflowStatus,
        currentStepId = request.outcome?.lastResumableStep ?: record.currentStepId,
        stepUpdates = null,
        artifactsPatch = continuationPatch + reviewStatePatch + outcomePatch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
    true
  }

  fun reviewState(workflowId: String, dbOverride: String? = null): GoalSubtaskReviewState? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      reviewStateFromArtifacts(decodeArtifacts(record.artifactsJson))
    }

  fun continuation(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeGoalContinuationArtifact? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      continuationFromArtifacts(decodeArtifacts(record.artifactsJson))
    }

  internal fun reserveGoalReviewPass(
    workflowId: String,
    dbOverride: String? = null,
  ): GoalSubtaskReviewPassReservation = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction GoalSubtaskReviewPassReservation.MissingState
    val artifacts = decodeArtifacts(record.artifactsJson)
    val state = reviewStateFromArtifacts(artifacts)
      ?: return@transaction GoalSubtaskReviewPassReservation.MissingState
    if (state.reviewCapReached || state.reviewSkippedByUser || state.completedPassCount >= 2) {
      return@transaction GoalSubtaskReviewPassCarryForward(state)
    }
    if (state.reservedPassNumber != null) {
      return@transaction GoalSubtaskReviewPassInFlight(state)
    }
    val reserved = state.reserveNextPass()
    if (reserved != state) {
      savePatch(
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
    check(state.reviewBaseSha == input.reviewBaseSha) {
      "Goal-subtask review input does not match the durable review baseline."
    }
    val updated = state.copy(reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY)
    savePatch(
      record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to updated.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY to input.toArtifactMap(),
      ),
    )
    updated
  }

  internal fun completeGoalReviewPass(
    request: GoalReviewPassCompletionRequest,
    dbOverride: String? = null,
  ): GoalSubtaskReviewState? = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val state = reviewStateFromArtifacts(artifacts)
      ?: return@transaction null
    require(request.rawReviewResult.isNotBlank()) { "Goal-subtask review pass result must be non-blank." }
    val previousResults = rawReviewResultsFromArtifacts(artifacts, state)
    val completed = state.completeReservedPass(request.verdict, request.unresolvedFindingCount, request.findings)
    val passNumber = completed.completedPassCount.toString()
    savePatch(
      record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to completed.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to (previousResults + (passNumber to request.rawReviewResult)),
      ),
    )
    completed
  }

  internal fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: java.nio.file.Path,
    dbOverride: String? = null,
  ): GoalSubtaskReviewInputPreparation {
    val durable = database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val state = reviewStateFromArtifacts(artifacts)
        ?: return@read null
      val continuation = continuationFromArtifacts(artifacts)
        ?: return@read null
      state to continuation
    } ?: return GoalSubtaskReviewInputPreparation.MissingState
    val (state, continuation) = durable
    val result = gitOperations.buildGoalSubtaskReviewInput(
      repoRoot,
      GoalSubtaskReviewBaseline(state.reviewBaseSha, state.baselineUntrackedPaths),
      continuation.goalBranch,
    )
    val input = if (result.ok) {
      requireNotNull(result.input)
    } else {
      recoverGoalReviewInput(
        GoalReviewInputRecoveryRequest(
          workflowId = workflowId,
          state = state,
          continuation = continuation,
          failureReason = result.failureReason,
          failureMessage = result.error,
          execution = GoalReviewInputRecoveryExecution(gitOperations, repoRoot, dbOverride),
        ),
      ) ?: return GoalSubtaskReviewInputBlocked(result.error)
    }
    val persisted = persistGoalReviewInput(workflowId, input, dbOverride)
      ?: return GoalSubtaskReviewInputPreparation.MissingState
    return GoalSubtaskReviewInputReady(persisted, input)
  }

  private fun recoverGoalReviewInput(request: GoalReviewInputRecoveryRequest): GoalSubtaskReviewInput? {
    if (request.failureReason !in recoverableReviewBaseFailures || !request.state.canRecoverReviewBase()) return null
    val recovered = request.execution.gitOperations.recoverGoalSubtaskReviewBaseline(
      request.execution.repoRoot,
      GoalSubtaskReviewBaseline(request.state.reviewBaseSha, request.state.baselineUntrackedPaths),
      request.continuation.goalBranch,
    )
    if (!recovered.ok) return null
    val recoveredBaseline = requireNotNull(recovered.baseline)
    val rebuilt = request.execution.gitOperations.buildGoalSubtaskReviewInput(
      request.execution.repoRoot,
      recoveredBaseline,
      request.continuation.goalBranch,
    )
    check(rebuilt.ok) {
      "Recovered goal-subtask review base '${recoveredBaseline.reviewBaseSha}' could not materialize review input " +
        "after replacing incompatible base '${request.state.reviewBaseSha}': " +
        rebuilt.error.ifBlank { request.failureMessage }
    }
    val input = requireNotNull(rebuilt.input)
    return persistRecoveredGoalReviewInput(
      request.workflowId,
      request.state,
      recoveredBaseline,
      input,
      request.execution.dbOverride,
    )?.let { input }
  }

  private fun persistRecoveredGoalReviewInput(
    workflowId: String,
    current: GoalSubtaskReviewState,
    baseline: GoalSubtaskReviewBaseline,
    input: GoalSubtaskReviewInput,
    dbOverride: String?,
  ): GoalSubtaskReviewState? = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
    check(latest == current && latest.canRecoverReviewBase()) {
      "Goal-subtask review base can be recovered only before any review input or completed review pass exists."
    }
    val replaced = latest.copy(
      reviewBaseSha = baseline.reviewBaseSha,
      baselineUntrackedPaths = baseline.baselineUntrackedPaths.distinct().sorted(),
      reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
    )
    check(input.reviewBaseSha == replaced.reviewBaseSha) {
      "Recovered goal-subtask review input does not match the replacement baseline."
    }
    savePatch(
      record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to replaced.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY to input.toArtifactMap(),
      ),
    )
    replaced
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

  private val savePatch =
    fun(
      record: skillbill.workflow.model.WorkflowStateSnapshot,
      workflowStates: skillbill.ports.persistence.WorkflowStateRepository,
      patch: Map<String, Any?>,
    ) {
      val updated = engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = patch,
          sessionId = record.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
    }
}

internal data class GoalContinuationStateRecordRequest(
  val workflowId: String,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact? = null,
  val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  val outcome: FeatureTaskRuntimeGoalContinuationOutcome? = null,
  val workflowStatus: String? = null,
)

internal data class GoalReviewPassCompletionRequest(
  val workflowId: String,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
)

private data class GoalReviewInputRecoveryRequest(
  val workflowId: String,
  val state: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val failureReason: GoalSubtaskReviewInputFailureReason?,
  val failureMessage: String,
  val execution: GoalReviewInputRecoveryExecution,
)

private data class GoalReviewInputRecoveryExecution(
  val gitOperations: WorkflowGitOperations,
  val repoRoot: java.nio.file.Path,
  val dbOverride: String?,
)

private fun continuationPatch(
  continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
  existing: FeatureTaskRuntimeGoalContinuationArtifact?,
): Map<String, Any?> = if (continuation != null && existing == null) {
  mapOf(
    FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
    "install_sync_result" to mapOf(
      "status" to "skipped",
      "reason" to "goal-continuation forbids installer, uninstall, and install-sync flows",
    ),
  )
} else {
  emptyMap()
}

private fun reviewStatePatch(
  request: GoalContinuationStateRecordRequest,
  artifacts: Map<String, Any?>,
  existingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
): Map<String, Any?> {
  val continuation = request.continuation ?: return emptyMap()
  val state = reviewStateFromArtifacts(artifacts)
  val baseline = request.reviewBaseline
  if (state != null) {
    check(baseline == null || state.matches(baseline, continuation)) {
      "Goal-subtask review baseline and execution mode are immutable on resume."
    }
    return emptyMap()
  }
  check(existingContinuation == null) {
    "Goal-subtask review state is missing for an existing child workflow; " +
      "refusing to capture a replacement baseline."
  }
  requireNotNull(baseline) {
    "Goal-subtask review baseline is required when opening a child workflow; " +
      "refusing to create an unpinned review scope."
  }
  if (GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY in artifacts) {
    rawReviewResultError(
      GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
      "must be absent before the goal-subtask review state exists.",
    )
  }
  return mapOf(
    GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to GoalSubtaskReviewState.initial(
      reviewBaseSha = baseline.reviewBaseSha,
      baselineUntrackedPaths = baseline.baselineUntrackedPaths,
      codeReviewMode = continuation.codeReviewMode,
    ).toArtifactMap(),
  )
}

internal sealed interface GoalSubtaskReviewPassReservation {
  data object MissingState : GoalSubtaskReviewPassReservation
}

internal data class GoalSubtaskReviewPassReserved(val state: GoalSubtaskReviewState) : GoalSubtaskReviewPassReservation
internal data class GoalSubtaskReviewPassInFlight(val state: GoalSubtaskReviewState) : GoalSubtaskReviewPassReservation
internal data class GoalSubtaskReviewPassCarryForward(
  val state: GoalSubtaskReviewState,
) : GoalSubtaskReviewPassReservation

internal sealed interface GoalSubtaskReviewInputPreparation {
  data object MissingState : GoalSubtaskReviewInputPreparation
}

internal data class GoalSubtaskReviewInputBlocked(val reason: String) : GoalSubtaskReviewInputPreparation
internal data class GoalSubtaskReviewInputReady(
  val state: GoalSubtaskReviewState,
  val input: GoalSubtaskReviewInput,
) : GoalSubtaskReviewInputPreparation

private val recoverableReviewBaseFailures: Set<GoalSubtaskReviewInputFailureReason> = setOf(
  GoalSubtaskReviewInputFailureReason.BASE_MISSING,
  GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
)

private fun continuationFromArtifacts(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? =
  GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.continuation

private fun reviewStateFromArtifacts(artifacts: Map<String, Any?>): GoalSubtaskReviewState? =
  GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state

private fun GoalSubtaskReviewState.canRecoverReviewBase(): Boolean = completedPassCount == 0 &&
  passResults.isEmpty() &&
  emittedPassCount == 0 &&
  reviewInputArtifact == null &&
  disposition == GoalSubtaskReviewDisposition.PENDING

private fun GoalSubtaskReviewState.matches(
  baseline: GoalSubtaskReviewBaseline,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
): Boolean = reviewBaseSha == baseline.reviewBaseSha &&
  baselineUntrackedPaths == baseline.baselineUntrackedPaths.distinct().sorted() &&
  codeReviewMode == continuation.codeReviewMode

private fun rawReviewResultsFromArtifacts(
  artifacts: Map<String, Any?>,
  state: GoalSubtaskReviewState,
): Map<String, String> {
  val decoded = GoalSubtaskReviewArtifactDecoder.decode(artifacts)
    ?: rawReviewResultError(
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
      "must be present whenever raw goal-subtask review results are read.",
    )
  if (decoded.state != state) {
    rawReviewResultError(
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
      "changed while reading its durable raw review results.",
    )
  }
  return decoded.rawResults
}

private fun rawReviewResultError(fieldPath: String, reason: String): Nothing =
  throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = fieldPath,
    reason = reason,
  )
