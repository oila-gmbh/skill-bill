package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
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
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting
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
    check(existingContinuation.compatibleWith(request.continuation)) {
      "Goal continuation is immutable for workflow '${request.workflowId}'; " +
        "parent, subtask, branch, and review mode cannot change on resume."
    }
    val continuationPatch = continuationPatch(request.continuation, existingContinuation)
    val reviewStatePatch = reviewStatePatch(request, artifacts, existingContinuation)
    val outcomePatch = request.outcome?.let {
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY to it.toArtifactMap())
    }.orEmpty()
    val adoptionPatch = request.fieldAdoption?.let {
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY to it.toArtifactMap())
    }.orEmpty()
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = request.workflowStatus ?: record.workflowStatus,
        currentStepId = request.outcome?.lastResumableStep ?: record.currentStepId,
        stepUpdates = null,
        artifactsPatch = continuationPatch + reviewStatePatch + outcomePatch + adoptionPatch,
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
    // An operator-granted retry round re-opens the consumed final pass instead of carrying its stale
    // result forward, so the fix the operator paid for is actually re-reviewed. The pass number is
    // unchanged: no new pass is reserved.
    val retryReopened = state.reserveNextPass()
    if (state.retryReviewPending && retryReopened != state) {
      // The raw results map is keyed by completed pass and is validated against passResults on every
      // read, so dropping the re-opened pass's result in the same patch is what keeps the record
      // decodable rather than leaving an orphaned entry behind.
      val keptResults = rawReviewResultsFromArtifacts(artifacts, state)
        .filterKeys { passNumber -> passNumber != state.completedPassCount.toString() }
      savePatch(
        record,
        unitOfWork.workflowStates,
        mapOf(
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to retryReopened.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to keptResults,
        ),
      )
      return@transaction GoalSubtaskReviewPassReserved(retryReopened)
    }
    if (state.reviewCapReached || state.reviewSkippedByUser) {
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
    check(input.reviewBaseSha == state.reviewBaseSha || input.reviewBaseSha == state.remediationBaseSha) {
      "Goal-subtask review input does not match the durable review baseline or its recorded remediation base."
    }
    val updated = state.copy(
      reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
      // The staleness check recomputes this digest from the immutable baseline, so only an
      // immutable-baseline input may set it. A remediation-scoped digest could never match that
      // recomputation, which would judge every capped subtask stale and reopen it on each resume.
      reviewedDeltaDigest = if (input.reviewBaseSha == state.reviewBaseSha) {
        input.deltaDigest
      } else {
        state.reviewedDeltaDigest
      },
    )
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

  /**
   * The single read-modify-write seam for the durable review state. Every per-field mutator routes
   * through it so they cannot drift on transaction shape, missing-record handling, or the artifact
   * key they patch. A transform that returns its input is a no-op write.
   */
  fun updateReviewState(
    workflowId: String,
    dbOverride: String? = null,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val state = reviewStateFromArtifacts(decodeArtifacts(record.artifactsJson)) ?: return@transaction null
    val updated = transform(state)
    if (updated == state) return@transaction state
    savePatch(
      record,
      unitOfWork.workflowStates,
      mapOf(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to updated.toArtifactMap()),
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
    val completed = state.completeReservedPass(
      request.verdict,
      request.unresolvedFindingCount,
      request.findings,
      request.blockerDispositions,
      request.commitFocusedAccounting,
    )
    val passNumber = completed.completedPassCount.toString()
    val continuation = continuationFromArtifacts(artifacts)
      ?: error("Goal-subtask review continuation is missing during reserved-pass recovery.")
    val ledgerFindings = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output = request.normalizedOutput,
      issueKey = continuation.issueKey,
      subtaskId = continuation.subtaskId,
      workflowId = request.workflowId,
      reviewPassNumber = passNumber.toInt(),
    )
    val supersededFindings = unitOfWork.unaddressedFindings.fetchWorkflowLedger(request.workflowId)
    unitOfWork.unaddressedFindings.replaceLedgerForPass(request.workflowId, passNumber.toInt(), ledgerFindings)
    unitOfWork.unaddressedFindings.recordOutcomes(
      GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
        supersededFindings = supersededFindings,
        currentFindings = ledgerFindings,
        blockerDispositions = request.blockerDispositions,
      ),
    )
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

  /**
   * Scope tuning for [buildGoalReviewInput].
   *
   * @param dbOverride optional workflow-store override for the caller's run.
   * @param scopedUntrackedExclusions when present, supersedes the durable baseline untracked
   * inventory as the exclusion list. The caller widens it to every untracked path the run does not
   * own, so foreign worktree dirt cannot enter the input. Only the exclusion list is affected; the
   * remediation base-sha rescoping below is untouched.
   * @param ownedPathspec the workflow-owned inventory the tracked delta is limited to. It bounds
   * which tracked files the input may contain; the base sha it is measured from is unaffected.
   */
  internal class GoalReviewInputScope(
    val dbOverride: String? = null,
    val scopedUntrackedExclusions: List<String>? = null,
    val ownedPathspec: List<String> = emptyList(),
  )

  internal fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: java.nio.file.Path,
    scope: GoalReviewInputScope = GoalReviewInputScope(),
  ): GoalSubtaskReviewInputPreparation {
    val durable = database.read(scope.dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val state = reviewStateFromArtifacts(artifacts)
        ?: return@read null
      val continuation = continuationFromArtifacts(artifacts)
        ?: return@read null
      state to continuation
    } ?: return GoalSubtaskReviewInputPreparation.MissingState
    val (state, continuation) = durable
    // Pass one is unchanged: the immutable review_base_sha and baseline untracked inventory stay its
    // sole authority. Every remediation pass from two onward is rescoped to that round's
    // diff(pre-fix tree -> HEAD), so the scope union the prompt states has a materialized input
    // behind it.
    val exclusions = scope.scopedUntrackedExclusions ?: state.baselineUntrackedPaths
    val remediationBaseline = state.remediationBaseSha
      ?.takeIf { (state.reservedPassNumber ?: 0) >= 2 }
      // The untracked exclusion list is not a per-pass detail: dropping it would materialize every
      // untracked file in the worktree into the pass-two input as an owned change. Only the base sha
      // is rescoped.
      ?.let { preFixSha -> GoalSubtaskReviewBaseline(preFixSha, exclusions, scope.ownedPathspec) }
    val result = gitOperations.buildGoalSubtaskReviewInput(
      repoRoot,
      remediationBaseline ?: GoalSubtaskReviewBaseline(state.reviewBaseSha, exclusions, scope.ownedPathspec),
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
          execution = GoalReviewInputRecoveryExecution(gitOperations, repoRoot, scope.dbOverride),
        ),
      ) ?: return GoalSubtaskReviewInputBlocked(result.error)
    }
    val persisted = persistGoalReviewInput(workflowId, input, scope.dbOverride)
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
    // Persisting the recovered baseline and its input is the last step of recovery, not a separate
    // seam: they must land in one transaction that re-reads the record and re-checks recoverability.
    val persisted = database.transaction(request.execution.dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction null
      val latest = reviewStateFromArtifacts(decodeArtifacts(record.artifactsJson)) ?: return@transaction null
      check(latest == request.state && latest.canRecoverReviewBase()) {
        "Goal-subtask review base can be recovered only before any review input or completed review pass exists."
      }
      val replaced = latest.copy(
        reviewBaseSha = recoveredBaseline.reviewBaseSha,
        baselineUntrackedPaths = recoveredBaseline.baselineUntrackedPaths.distinct().sorted(),
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
    return persisted?.let { input }
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
  val fieldAdoption: FeatureTaskRuntimeGoalContinuationFieldAdoption? = null,
  val workflowStatus: String? = null,
)

internal data class GoalReviewPassCompletionRequest(
  val workflowId: String,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val normalizedOutput: Map<String, Any?>,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  /** Present only when the pass ran delegated over a real commit sequence. */
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
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
): Map<String, Any?> = when {
  continuation == null || continuation == existing -> emptyMap()
  existing == null -> mapOf(
    FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
    "install_sync_result" to mapOf(
      "status" to "deferred",
      "reason" to
        "goal-continuation defers installer, uninstall, and install-sync flows until the parent goal exits; " +
        "deferred install sync must not block subtask completion",
    ),
  )
  else -> mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap())
}

private fun FeatureTaskRuntimeGoalContinuationArtifact?.compatibleWith(
  supplied: FeatureTaskRuntimeGoalContinuationArtifact?,
): Boolean {
  if (this == null || supplied == null) return true
  // Agent add-ons are launch guidance and may change on resume. An absent validation depth may be
  // healed to the launcher-supplied value in the same write; a recorded depth remains immutable.
  val healed = copy(
    agentAddonSelection = skillbill.agentaddon.model.AgentAddonSelection(),
    validationDepth = validationDepth ?: supplied.validationDepth,
  )
  return healed == supplied.copy(agentAddonSelection = skillbill.agentaddon.model.AgentAddonSelection())
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
