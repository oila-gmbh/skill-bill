package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.resolveCheckpointRef
import skillbill.ports.workflow.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.unionRefutedBlockerDispositions

@Inject
@Suppress("TooManyFunctions") // one cohesive goal-continuation recorder; each method is a distinct durable seam
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
    // The manifest subtask name reaches the row only from the goal parent. A launcher that supplies
    // none must not erase it, or the provisional commit subject silently regresses to the fallback.
    val supplied = request.continuation?.let { continuation ->
      continuation.copy(subtaskName = continuation.subtaskName ?: existingContinuation?.subtaskName)
    }
    check(existingContinuation.compatibleWith(supplied)) {
      "Goal continuation is immutable for workflow '${request.workflowId}'; " +
        "parent, subtask, branch, and review mode cannot change on resume."
    }
    val continuationPatch = continuationPatch(supplied, existingContinuation)
    val reviewStatePatch = reviewStatePatch(request.copy(continuation = supplied), artifacts, existingContinuation)
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
    val loaded = loadGoalReviewPassWrite(unitOfWork, request) ?: return@transaction null
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
    val dispositions = if (reservedPass <= 1) {
      request.blockerDispositions
    } else {
      unionRefutedBlockerDispositions(
        request.blockerDispositions,
        GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(
          supersededFindings,
          ledgerFindings,
          recordedVerdicts,
        ),
      )
    }
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
    savePatch(
      loaded.record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to completed.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to (loaded.previousResults + (passNumber to request.rawReviewResult)),
      ),
    )
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

  @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
  // SKILL-176: recovery branches are distinct guard exits
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
    val selectedBaseline = remediationBaseline
      ?: GoalSubtaskReviewBaseline(state.reviewBaseSha, exclusions, scope.ownedPathspec)
    val failedField = if (remediationBaseline != null) {
      GoalReviewBaseField.REMEDIATION_BASE
    } else {
      GoalReviewBaseField.REVIEW_BASE
    }
    val result = gitOperations.buildGoalSubtaskReviewInput(
      repoRoot,
      selectedBaseline,
      continuation.goalBranch,
    )
    val input = if (result.ok) {
      requireNotNull(result.input)
    } else {
      when (
        val recovery = recoverGoalReviewInput(
          GoalReviewInputRecoveryRequest(
            workflowId = workflowId,
            state = state,
            continuation = continuation,
            failureReason = result.failureReason,
            failureMessage = result.error,
            failedBaseSha = selectedBaseline.reviewBaseSha,
            failedField = failedField,
            scope = scope,
            execution = GoalReviewInputRecoveryExecution(gitOperations, repoRoot, scope.dbOverride),
          ),
        )
      ) {
        is GoalReviewInputRecovery.Recovered -> recovery.input
        is GoalReviewInputRecovery.Failed -> return GoalSubtaskReviewInputBlocked(recovery.reason)
        GoalReviewInputRecovery.Ineligible -> return GoalSubtaskReviewInputBlocked(result.error)
      }
    }
    val persisted = persistGoalReviewInput(workflowId, input, scope.dbOverride)
      ?: return GoalSubtaskReviewInputPreparation.MissingState
    return GoalSubtaskReviewInputReady(persisted, input)
  }

  @Suppress("LongMethod") // SKILL-176: recovery persists baseline, input, and evidence in one transaction
  private fun recoverGoalReviewInput(request: GoalReviewInputRecoveryRequest): GoalReviewInputRecovery {
    val failureReason = request.failureReason
    if (failureReason == null ||
      failureReason !in recoverableReviewBaseFailures ||
      !request.state.canRecoverReviewBase()
    ) {
      return GoalReviewInputRecovery.Ineligible
    }
    val exclusions = request.scope.scopedUntrackedExclusions ?: request.state.baselineUntrackedPaths
    val recovered = request.execution.gitOperations.recoverGoalSubtaskReviewBaseline(
      request.execution.repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = request.failedBaseSha,
        failureReason = failureReason,
        baselineUntrackedPaths = exclusions,
        ownedPathspec = request.scope.ownedPathspec,
      ),
      request.continuation.goalBranch,
    )
    if (!recovered.ok) {
      return GoalReviewInputRecovery.Failed(
        recovered.error.ifBlank {
          "Goal-subtask review baseline recovery could not find a reachable base for unreachable sha " +
            "'${request.failedBaseSha}' on branch '${request.continuation.goalBranch}'."
        },
      )
    }
    val recoveredBaseline = requireNotNull(recovered.baseline)
    val rebuilt = request.execution.gitOperations.buildGoalSubtaskReviewInput(
      request.execution.repoRoot,
      recoveredBaseline,
      request.continuation.goalBranch,
    )
    check(rebuilt.ok) {
      "Recovered goal-subtask review base '${recoveredBaseline.reviewBaseSha}' could not materialize review input " +
        "after replacing incompatible base '${request.failedBaseSha}': " +
        rebuilt.error.ifBlank { request.failureMessage }
    }
    val input = requireNotNull(rebuilt.input)
    // Persisting the recovered baseline, its input, and recovery evidence is the last step of
    // recovery, not a separate seam: they must land in one transaction that re-reads the record and
    // re-checks recoverability.
    val persisted = database.transaction(request.execution.dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
      check(latest == request.state && latest.canRecoverReviewBase()) {
        "Goal-subtask review base can be recovered only while disposition is still pending."
      }
      val replaced = when (request.failedField) {
        GoalReviewBaseField.REMEDIATION_BASE -> latest.copy(
          remediationBaseSha = recoveredBaseline.reviewBaseSha,
          reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
        )
        GoalReviewBaseField.REVIEW_BASE -> latest.copy(
          reviewBaseSha = recoveredBaseline.reviewBaseSha,
          baselineUntrackedPaths = recoveredBaseline.baselineUntrackedPaths.distinct().sorted(),
          reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
        )
      }
      check(
        input.reviewBaseSha == replaced.reviewBaseSha ||
          input.reviewBaseSha == replaced.remediationBaseSha,
      ) {
        "Recovered goal-subtask review input does not match the replacement baseline."
      }
      val evidenceEntry = linkedMapOf<String, Any?>(
        "original_sha" to request.failedBaseSha,
        "replacement_sha" to recoveredBaseline.reviewBaseSha,
        "repointed_field" to request.failedField.wireValue,
        "failure_reason" to failureReason.name.lowercase(),
        "failure_message" to request.failureMessage,
        "goal_branch" to request.continuation.goalBranch,
      )
      val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
      savePatch(
        record,
        unitOfWork.workflowStates,
        mapOf(
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to replaced.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY to input.toArtifactMap(),
          GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry,
        ),
      )
      replaced
    }
    return if (persisted != null) {
      GoalReviewInputRecovery.Recovered(input)
    } else {
      GoalReviewInputRecovery.Ineligible
    }
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

  /**
   * Resume-side coherence for the remediation checkpoint commit → base-record window (SKILL-176).
   *
   * Advances `remediation_base_sha` to the latest review_fix checkpoint ref that resolves, not to
   * branch ancestry against HEAD. Every heal or blocked reconciliation appends durable evidence under
   * [GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] with an explicit reason. Returns [RemediationBaseCoherenceResult.Coherent]
   * when reconciliation completes or is unnecessary, or [RemediationBaseCoherenceResult.Blocked] when no
   * remediation base can be resolved without rewriting to HEAD.
   */
  @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
  internal fun reconcileRemediationBaseCoherence(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: java.nio.file.Path,
    dbOverride: String? = null,
  ): RemediationBaseCoherenceResult {
    val snapshot = database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      runCatching {
        val state = reviewStateFromArtifacts(artifacts) ?: return@read null
        val continuation = continuationFromArtifacts(artifacts) ?: return@read null
        val checkpoints = featureTaskRuntimeCheckpointIdentitiesFromArtifact(
          artifacts[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY],
        )
        Triple(state, continuation, checkpoints)
      }.getOrElse { error ->
        if (error is InvalidGoalSubtaskReviewStateSchemaError) return@read null else throw error
      }
    } ?: return RemediationBaseCoherenceResult.Coherent(null)
    val (state, continuation, checkpoints) = snapshot
    if (state.remediationBaseSha == null &&
      checkpoints.none { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
    ) {
      return RemediationBaseCoherenceResult.Coherent(state)
    }
    val latestRemediationResolved = latestResolvedReviewFixCheckpointCommit(
      checkpoints = checkpoints,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
    )
    val stored = state.remediationBaseSha
    val storedResolves = stored?.let { resolvesCommit(gitOperations, repoRoot, it) } == true
    fun isStrictAncestor(ancestor: String, descendant: String): Boolean {
      if (ancestor == descendant) return false
      val ancestry = gitOperations.isCommitAncestor(repoRoot, ancestor, descendant)
      return ancestry.ok && ancestry.value == "true"
    }
    val reconciliation = when {
      latestRemediationResolved != null &&
        (stored == null || isStrictAncestor(stored, latestRemediationResolved.sha)) ->
        ReconciliationDecision.Heal(latestRemediationResolved.sha)
      stored != null && storedResolves -> {
        val head = gitOperations.headCommitSha(repoRoot)
        if (!head.ok || head.value.isBlank()) {
          ReconciliationDecision.Coherent
        } else {
          val headSha = head.value.trim()
          val onBranch = gitOperations.isCommitAncestor(repoRoot, stored, headSha)
          when {
            !onBranch.ok -> ReconciliationDecision.Coherent
            onBranch.value == "true" -> ReconciliationDecision.Coherent
            latestRemediationResolved != null ->
              ReconciliationDecision.Heal(latestRemediationResolved.sha)
            else -> ReconciliationDecision.Blocked
          }
        }
      }
      latestRemediationResolved != null -> ReconciliationDecision.Heal(latestRemediationResolved.sha)
      stored != null && !storedResolves -> ReconciliationDecision.Blocked
      else -> ReconciliationDecision.Blocked
    }
    when (reconciliation) {
      ReconciliationDecision.Coherent -> return RemediationBaseCoherenceResult.Coherent(state)
      ReconciliationDecision.Blocked -> {
        val failedRef = latestReviewFixCheckpointRef(checkpoints)
        val guidance = remediationBaseReconciliationBlockedGuidance(
          workflowId = workflowId,
          issueKey = continuation.issueKey,
          subtaskId = continuation.subtaskId,
          goalBranch = continuation.goalBranch,
          failedRef = failedRef,
          storedSha = stored,
        )
        appendRemediationBaseReconciliationEvidence(
          workflowId = workflowId,
          continuation = continuation,
          stored = stored,
          target = null,
          reason = "reconciliation_blocked",
          failureMessage = guidance,
          seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
          valueUsed = failedRef ?: stored.orEmpty(),
          valueExpected = "resolvable review_fix checkpoint ref commit",
          cause = remediationBlockedCause(stored, storedResolves, failedRef),
          dbOverride = dbOverride,
        )
        return RemediationBaseCoherenceResult.Blocked(guidance)
      }
      is ReconciliationDecision.Heal -> {
        val target = reconciliation.sha
        if (target == stored) return RemediationBaseCoherenceResult.Coherent(state)
        val reason = when {
          stored == null -> "committed_but_unrecorded"
          latestRemediationResolved != null && latestRemediationResolved.sha == target ->
            "committed_but_unrecorded"
          else -> "recorded_but_superseded"
        }
        if (!storedResolves && stored != null) {
          appendRemediationBaseReconciliationEvidence(
            workflowId = workflowId,
            continuation = continuation,
            stored = stored,
            target = target,
            reason = reason,
            failureMessage =
              "Resume reconciled remediation_base_sha ($reason) through checkpoint ref after stored base missed.",
            seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
            valueUsed = stored,
            valueExpected = "resolvable remediation_base_sha commit",
            cause = "stored remediation base did not resolve; reconciled through checkpoint ref",
            dbOverride = dbOverride,
          )
        }
        val headSha = gitOperations.headCommitSha(repoRoot).value.orEmpty().trim()
        val healed = database.transaction(dbOverride) { unitOfWork ->
          val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
            ?: return@transaction null
          val artifacts = decodeArtifacts(record.artifactsJson)
          val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
          if (latest.remediationBaseSha == target) return@transaction latest
          val updated = latest.copy(remediationBaseSha = target)
          val evidenceEntry = remediationBaseRecoveryEvidenceEntry(
            originalSha = stored,
            replacementSha = target,
            reason = reason,
            goalBranch = continuation.goalBranch,
            headSha = headSha,
            seam = null,
            valueUsed = null,
            valueExpected = null,
            cause = null,
          )
          val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
          savePatch(
            record,
            unitOfWork.workflowStates,
            mapOf(
              GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to updated.toArtifactMap(),
              GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry,
            ),
          )
          updated
        }
        return RemediationBaseCoherenceResult.Coherent(healed ?: state)
      }
    }
  }

  private sealed interface ReconciliationDecision {
    data object Coherent : ReconciliationDecision
    data object Blocked : ReconciliationDecision
    data class Heal(val sha: String) : ReconciliationDecision
  }

  private fun remediationBlockedCause(
    stored: String?,
    storedResolves: Boolean,
    failedRef: String?,
  ): String = when {
    stored != null && !storedResolves ->
      "stored remediation_base_sha '$stored' did not resolve to a commit"
    failedRef != null ->
      "checkpoint ref '$failedRef' did not resolve to a commit"
    else -> "no review_fix checkpoint ref resolved to a commit"
  }

  private data class ResolvedReviewFixCheckpoint(val identity: FeatureTaskRuntimeCheckpointIdentity, val sha: String)

  private fun latestResolvedReviewFixCheckpointCommit(
    checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
    gitOperations: WorkflowGitOperations,
    repoRoot: java.nio.file.Path,
  ): ResolvedReviewFixCheckpoint? = checkpoints
    .asReversed()
    .firstNotNullOfOrNull { identity ->
      if (identity.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return@firstNotNullOfOrNull null
      resolveCheckpointRefCommit(gitOperations, repoRoot, identity.checkpointRef)
        ?.let { ResolvedReviewFixCheckpoint(identity, it) }
    }

  private fun latestReviewFixCheckpointRef(checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>): String? =
    checkpoints
      .asReversed()
      .firstOrNull { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
      ?.checkpointRef

  private fun resolveCheckpointRefCommit(
    gitOperations: WorkflowGitOperations,
    repoRoot: java.nio.file.Path,
    checkpointRef: String,
  ): String? {
    val resolved = gitOperations.resolveCheckpointRef(
      repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      checkpointRef,
    )
    if (!resolved.ok) return null
    return resolved.value.orEmpty().trim().takeIf(String::isNotBlank)
  }

  private fun resolvesCommit(
    gitOperations: WorkflowGitOperations,
    repoRoot: java.nio.file.Path,
    sha: String,
  ): Boolean {
    val resolved = gitOperations.resolveCommit(repoRoot, sha.trim())
    return resolved.ok && resolved.value.orEmpty().trim().isNotBlank()
  }

  private fun remediationBaseReconciliationBlockedGuidance(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    goalBranch: String,
    failedRef: String?,
    storedSha: String?,
  ): String {
    val refDetail = failedRef?.let { "checkpoint ref '$it'" } ?: "stored remediation base"
    val storedDetail = storedSha?.let { " (stored sha '$it' also failed to resolve)" }.orEmpty()
    return "Remediation base reconciliation blocked for workflow '$workflowId' on branch '$goalBranch': " +
      "$refDetail could not be resolved to a commit$storedDetail. " +
      "Run `skill-bill goal repair --issue-key $issueKey --subtask $subtaskId --apply` to repoint or clear " +
      "the unreachable remediation base, then resume the goal child."
  }

  internal fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    seam: String,
    valueUsed: String,
    valueExpected: String,
    cause: String,
    dbOverride: String?,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction
      val artifacts = decodeArtifacts(record.artifactsJson)
      val goalBranch = continuationFromArtifacts(artifacts)?.goalBranch.orEmpty()
      val evidenceEntry = remediationBaseRecoveryEvidenceEntry(
        originalSha = null,
        replacementSha = null,
        reason = "rollback_degradation",
        goalBranch = goalBranch,
        headSha = null,
        seam = seam,
        valueUsed = valueUsed,
        valueExpected = valueExpected,
        cause = cause,
        failureMessageOverride = "Remediation rollback degradation at $seam.",
      )
      val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
      savePatch(
        record,
        unitOfWork.workflowStates,
        mapOf(GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry),
      )
    }
  }

  private fun appendRemediationBaseReconciliationEvidence(
    workflowId: String,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    stored: String?,
    target: String?,
    reason: String,
    failureMessage: String,
    seam: String,
    valueUsed: String,
    valueExpected: String,
    cause: String,
    dbOverride: String?,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction
      val artifacts = decodeArtifacts(record.artifactsJson)
      val evidenceEntry = remediationBaseRecoveryEvidenceEntry(
        originalSha = stored,
        replacementSha = target,
        reason = reason,
        goalBranch = continuation.goalBranch,
        headSha = null,
        seam = seam,
        valueUsed = valueUsed,
        valueExpected = valueExpected,
        cause = cause,
        failureMessageOverride = failureMessage,
      )
      val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
      savePatch(
        record,
        unitOfWork.workflowStates,
        mapOf(GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry),
      )
    }
  }

  private fun remediationBaseRecoveryEvidenceEntry(
    originalSha: String?,
    replacementSha: String?,
    reason: String,
    goalBranch: String,
    headSha: String?,
    seam: String?,
    valueUsed: String?,
    valueExpected: String?,
    cause: String?,
    failureMessageOverride: String? = null,
  ): LinkedHashMap<String, Any?> {
    val failureMessage = failureMessageOverride ?: run {
      val headDetail = headSha?.takeIf(String::isNotBlank)?.let { " at HEAD '$it'" }.orEmpty()
      "Resume reconciled remediation_base_sha ($reason) so the recorded base stays reachable " +
        "from branch '$goalBranch'$headDetail."
    }
    return linkedMapOf<String, Any?>(
      "original_sha" to originalSha,
      "replacement_sha" to replacementSha,
      "repointed_field" to GoalReviewBaseField.REMEDIATION_BASE.wireValue,
      "failure_reason" to reason,
      "failure_message" to failureMessage,
      "goal_branch" to goalBranch,
    ).also { entry ->
      if (seam != null) entry["seam"] = seam
      if (valueUsed != null) entry["value_used"] = valueUsed
      if (valueExpected != null) entry["value_expected"] = valueExpected
      if (cause != null) entry["cause"] = cause
    }
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
  val failedBaseSha: String,
  val failedField: GoalReviewBaseField,
  val scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
  val execution: GoalReviewInputRecoveryExecution,
)

private enum class GoalReviewBaseField(val wireValue: String) {
  REVIEW_BASE("review_base_sha"),
  REMEDIATION_BASE("remediation_base_sha"),
}

private sealed interface GoalReviewInputRecovery {
  class Recovered(val input: GoalSubtaskReviewInput) : GoalReviewInputRecovery
  class Failed(val reason: String) : GoalReviewInputRecovery
  data object Ineligible : GoalReviewInputRecovery
}

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
  GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(artifacts)

private fun reviewStateFromArtifacts(artifacts: Map<String, Any?>): GoalSubtaskReviewState? =
  GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(artifacts)

private fun GoalSubtaskReviewState.canRecoverReviewBase(): Boolean = disposition == GoalSubtaskReviewDisposition.PENDING

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
