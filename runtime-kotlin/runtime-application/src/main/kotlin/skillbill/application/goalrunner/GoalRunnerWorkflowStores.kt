@file:Suppress("TooManyFunctions")

package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.decomposition.withParentStatus
import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.application.featuretask.FeatureTaskRuntimeCrashLiveness
import skillbill.application.featuretask.asPendingForOperatorResume
import skillbill.application.featuretask.phaseLedgerFrom
import skillbill.application.featuretask.phaseRecordsFrom
import skillbill.application.model.GoalRunnerChildRepairApplyResult
import skillbill.application.normalizeRequiredIssueKey
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.findDecomposedParentOrCorruptFallback
import skillbill.application.workflow.findDecomposedParentWorkflow
import skillbill.application.workflow.generateWorkflowId
import skillbill.application.workflow.repoRoot
import skillbill.application.workflow.requireRuntimeModeForEngineWrite
import skillbill.application.workflow.toRecord
import skillbill.application.workflow.toSnapshot
import skillbill.contracts.JsonSupport
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.LegacyProseWorkflowError
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.model.GoalRunnerProgressEvent
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.GoalChildWorkflowDeletionScope
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.GoalProgressEventValidator
import skillbill.workflow.NoopGoalObservabilityEventValidator
import skillbill.workflow.NoopGoalProgressEventValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepState
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.model.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifacts
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// SKILL-87: under requireStalenessEvidence, a running candidate counts as alive (not stale) when any
// declared-liveness or snapshot-update signal lands within this window. Generous enough that a long
// but legitimately quiet first phase is never mistaken for a dead subtask.
private const val STALENESS_EVIDENCE_WINDOW_MINUTES: Long = 30
private val STALENESS_EVIDENCE_WINDOW: Duration = Duration.ofMinutes(STALENESS_EVIDENCE_WINDOW_MINUTES)
private const val GOAL_REVIEW_POLICY_ARTIFACT_KEY = "goal_review_policy"
private const val GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY = "goal_out_of_band_acceptances"

// Sibling workflow artifact (not inside goal_continuation): no rejectUnknownGoalContinuationKeys registration.
private const val GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY =
  "goal_continuation_outcome_displacement"

private data class SavedGoalChildWorkflow(
  val state: GoalRunnerManifestState,
  val projectionArtifactsJson: String,
)

@Inject
// parent projection, controls, and child persistence share one transaction boundary and its dependencies
@Suppress("LargeClass", "LongParameterList")
class WorkflowGoalRunnerManifestStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
  private val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  private val clock: Clock = Clock.systemUTC(),
) : GoalRunnerManifestStore {
  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
    dbPathOverride: String?,
  ) = database.read(dbPathOverride) {
    it.goalPlanningPreparations.boundedStatus(
      parentWorkflowId,
      orderedSubtaskIds,
      blockedSubtaskId,
      blockedReason,
    )
  }

  override fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? =
    controls.executionLease(parentWorkflowId, dbPathOverride)

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = controls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken, dbPathOverride)

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = controls.heartbeatExecutionLease(parentWorkflowId, lease, dbPathOverride)

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = controls.releaseExecutionLease(parentWorkflowId, ownerToken, generation, dbPathOverride)

  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  private val planningHydrator = GoalChildPlanningHydrator(phaseOutputValidator, planningProjectionValidator)
  private val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
  private val controls = GoalRunnerControlCoordinator(
    database,
    decompositionManifestValidator,
    clock,
  ) { unitOfWork, state ->
    saveWorkflowProjectionInTransaction(unitOfWork, state)
  }

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> findProjectedManifest(root, issueKey) }
    val stored = loadFromWorkflowStore(issueKey, dbPathOverride, projected)
    if (shouldRefreshFromCompleteProjection(stored, projected)) {
      return saveWorkflowProjection(
        requireNotNull(stored).copy(manifest = requireNotNull(projected), repoRoot = repoRoot),
        dbPathOverride,
      ).state
    }
    return stored?.copy(repoRoot = repoRoot) ?: projected?.let { manifest ->
      importFromManifestProjection(manifest, dbPathOverride)?.copy(repoRoot = repoRoot)
    }
  }

  override fun readByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> findProjectedManifest(root, issueKey) }
    val stored = loadFromWorkflowStore(issueKey, dbPathOverride, projected)
    return readProjection(stored, projected, repoRoot, dbPathOverride)
  }

  override fun readByIssueKeyIfPresent(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> findProjectedManifest(root, issueKey, recoverPending = false) }
    val stored = loadFromWorkflowStoreIfPresent(issueKey, dbPathOverride, projected)
    return readProjection(stored, projected, repoRoot, dbPathOverride)
  }

  private fun readProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): GoalRunnerManifestState? {
    return when {
      shouldRefreshFromCompleteProjection(stored, projected) -> requireNotNull(stored).copy(
        manifest = requireNotNull(projected),
        repoRoot = repoRoot,
      )
      stored != null -> stored.copy(repoRoot = repoRoot)
      projected != null -> GoalRunnerManifestState(
        parentWorkflowId = "",
        dbPath = dbPathOverride.orEmpty(),
        manifest = projected,
        repoRoot = repoRoot,
      )
      else -> null
    }
  }

  override fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String?): GoalRunnerManifestState? =
    loadFromWorkflowStore(issueKey, dbPathOverride, currentProjectedManifest = null)

  private fun shouldRefreshFromCompleteProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
  ): Boolean = stored != null &&
    projected != null &&
    projected.isCompleteGoalProjection() &&
    !stored.manifest.isCompleteGoalProjection()

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    val saved = saveWorkflowProjection(state, dbPathOverride)
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: Path.of("").toAbsolutePath(),
      saved.projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
    return saved.state
  }

  override fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    saveWorkflowProjection(state, dbPathOverride).state

  override fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    controls.controlState(parentWorkflowId, dbPathOverride)

  override fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String?,
  ): GoalRunnerControlState = controls.bindRepositoryIdentity(parentWorkflowId, repositoryIdentity, dbPathOverride)

  override fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerLaunchAuthorization = controls.authorizeSubtaskLaunch(state, subtaskId, dbPathOverride)

  override fun authorizePlanningLaunch(parentWorkflowId: String, dbPathOverride: String?): AgentRunSpawnAuthorization =
    controls.planningSpawnAuthorization(parentWorkflowId, dbPathOverride)

  override fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerControlState = controls.persistStopAfterSubtask(parentWorkflowId, subtaskId, dbPathOverride)

  override fun requestPause(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState? =
    controls.requestPause(parentWorkflowId, dbPathOverride)

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
    dbPathOverride: String?,
  ): GoalRunnerControlState? =
    controls.pauseNow(parentWorkflowId, reason, pausedAt, overwriteExistingReason, dbPathOverride)

  override fun requestPauseByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerPausePersistenceResult? = controls.requestPauseByIssueKey(issueKey, dbPathOverride, repoRoot)

  override fun resume(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerManifestState? =
    controls.resume(parentWorkflowId, dbPathOverride)

  override fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    controls.pauseAtBoundary(state, dbPathOverride)

  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerCompletionPersistenceResult = controls.saveCompletedSubtaskAtBoundary(state, subtaskId, dbPathOverride)

  override fun saveHardReset(
    state: GoalRunnerManifestState,
    dbPathOverride: String?,
    preservePlanning: Boolean,
  ): GoalRunnerManifestState {
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      if (!preservePlanning) unitOfWork.goalPlanningPreparations.deleteByGoal(state.parentWorkflowId)
      unitOfWork.workflowStates.deleteGoalChildWorkflowsByParent(state.parentWorkflowId)
      val repositoryIdentity = unitOfWork.goalRunnerControls.controlState(state.parentWorkflowId).repositoryIdentity
      val projection = saveWorkflowProjectionInTransaction(
        unitOfWork,
        state,
        clearOutOfBandAcceptances = true,
        mergeConcurrentProgress = false,
      )
      if (repositoryIdentity != null) {
        unitOfWork.goalRunnerControls.persistControlState(
          projection.state.parentWorkflowId,
          projection.state.controlState.copy(repositoryIdentity = repositoryIdentity),
        )
      }
      projection.copy(
        state = projection.state.copy(
          controlState = projection.state.controlState.copy(repositoryIdentity = repositoryIdentity),
        ),
      )
    }
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: Path.of("").toAbsolutePath(),
      saved.projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
    return saved.state
  }

  override fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
    dbPathOverride: String?,
  ): GoalRunnerManifestState {
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      val selected = state.manifest.subtasks.singleOrNull { it.id == subtaskId }
        ?: error("Unknown or ambiguous goal subtask '$subtaskId'.")
      require(selected.workflowId == workflowId) {
        "Selected subtask '$subtaskId' does not own child workflow '$workflowId'."
      }
      val deleted = unitOfWork.workflowStates.deleteGoalChildWorkflow(
        state.parentWorkflowId,
        subtaskId,
        workflowId,
      )
      require(deleted == 1) {
        "Child workflow '$workflowId' is absent, compatible, or not owned by subtask '$subtaskId'."
      }
      val recoveredManifest = state.manifest.afterIncompatibleChildDeletion(subtaskId)
      saveWorkflowProjectionInTransaction(unitOfWork, state.copy(manifest = recoveredManifest))
    }
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: Path.of("").toAbsolutePath(),
      saved.projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
    return saved.state
  }

  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
    options: skillbill.ports.goalrunner.model.GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult {
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      executeScopedReplan(unitOfWork, state, subtaskId, options)
    }
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: Path.of("").toAbsolutePath(),
      saved.second,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
    return saved.first
  }

  private fun executeScopedReplan(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: skillbill.ports.goalrunner.model.GoalRunnerScopedReplanOptions,
  ): Pair<GoalRunnerScopedReplanWriteResult, String> {
    val preparations = unitOfWork.goalPlanningPreparations
    val plannedBefore = preparations.listPreparedPlanSubtaskIds(state.parentWorkflowId)
    val sharedBefore = preparations.hasPreparedSharedPreplan(state.parentWorkflowId)
    val discard = discardScopedReplanPlans(preparations, state, subtaskId, options, plannedBefore)
    val plannedAfter = preparations.listPreparedPlanSubtaskIds(state.parentWorkflowId)
    val sharedAfter = preparations.hasPreparedSharedPreplan(state.parentWorkflowId)
    // A child hydrated from the discarded plan still holds those planning bytes as its own import.
    // Leaving it behind makes the next launch fail the hydration provenance check, which used to
    // strand a scoped replan on advice to hard reset.
    val clearedChildIds = deleteStaleReplanChildren(
      unitOfWork,
      state,
      listOf(subtaskId) + discard.cascadedIds,
    )
    val projection = saveWorkflowProjectionInTransaction(
      unitOfWork,
      state.copy(manifest = state.manifest.afterReplanChildDeletion(clearedChildIds)),
      mergeConcurrentProgress = false,
    )
    return GoalRunnerScopedReplanWriteResult(
      state = projection.state,
      deletedPlanCount = discard.deleted,
      plannedSubtaskIdsBefore = plannedBefore,
      plannedSubtaskIdsAfter = plannedAfter,
      sharedPreplanPrepared = sharedAfter,
      sharedPreplanPreparedBefore = sharedBefore,
      discardedSharedPreplan = sharedBefore && !sharedAfter,
      cascadedPlanSubtaskIds = discard.cascadedIds,
      clearedChildSubtaskIds = clearedChildIds,
    ) to projection.projectionArtifactsJson
  }

  private data class ScopedReplanDiscard(
    val cascadedIds: List<Int>,
    val deleted: Int,
  )

  private fun discardScopedReplanPlans(
    preparations: skillbill.ports.persistence.GoalPlanningPreparationRepository,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: skillbill.ports.goalrunner.model.GoalRunnerScopedReplanOptions,
    plannedBefore: List<Int>,
  ): ScopedReplanDiscard {
    if (!options.includeSharedPreplan) {
      return ScopedReplanDiscard(
        cascadedIds = emptyList(),
        deleted = preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId),
      )
    }
    // Cascade only non-terminal-with-commit siblings. Terminal survivors stay so WE-4719-shaped
    // goals keep complete planning provenance; FK ON DELETE CASCADE must not wipe them.
    val cascadedIds = cascadeEligiblePlanSubtaskIds(
      plannedIds = plannedBefore.filter { it != subtaskId },
      subtasks = state.manifest.subtasks,
    )
    val retainedIds = plannedBefore.filter { it != subtaskId && it !in cascadedIds }
    val expectedDigest = options.expectedSharedPayloadSha256
    val deleted = if (expectedDigest != null) {
      val identity = requireNotNull(options.planningIdentity) {
        "planningIdentity is required when discarding a shared preplan by digest."
      }
      // Digest CAS first so a mismatch refuses with zero mutation.
      if (retainedIds.isEmpty()) {
        preparations.deleteSharedPreplan(identity, expectedDigest)
        if (subtaskId in plannedBefore) 1 else 0
      } else {
        preparations.invalidateSharedPreplan(identity, expectedDigest)
        cascadedIds.forEach { id -> preparations.deleteSubtaskPlan(state.parentWorkflowId, id) }
        preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId)
      }
    } else {
      cascadedIds.forEach { id -> preparations.deleteSubtaskPlan(state.parentWorkflowId, id) }
      preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId)
    }
    return ScopedReplanDiscard(cascadedIds = cascadedIds, deleted = deleted)
  }

  override fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String?): String? =
    database.read(dbPathOverride) {
      it.goalPlanningPreparations.sharedPreplanPayloadSha256(parentWorkflowId)
    }

  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String?,
  ): GoalRunnerManifestState {
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      saveNewChildWorkflowInTransaction(unitOfWork, state, setup)
    }
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: Path.of("").toAbsolutePath(),
      saved.projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
    return saved.state
  }

  private fun saveNewChildWorkflowInTransaction(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): SavedGoalChildWorkflow {
    requireConsistentChildSetup(state, setup)
    val expectedIdentity = expectedChildIdentity(setup)
    val parentUpdated = updateParentForChildWorkflow(unitOfWork, state)
    val existingChild = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, setup.workflowId)
    if (existingChild != null) {
      val persistedIdentity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(setup.workflowId)
      if (persistedIdentity != expectedIdentity) {
        throw IncompatibleGoalPlanningPreparationRecoveryError(
          state.parentWorkflowId,
          setup.subtaskId,
          "existing child execution identity conflicts with goal-child setup",
        )
      }
      requireMatchingGoalContinuation(existingChild, state, setup)
      planningHydrator.requireMatchingImport(unitOfWork, existingChild, setup)
    }
    val childUpdated = if (existingChild == null) {
      openGoalChildWorkflow(unitOfWork, state, setup, parentUpdated.workflowId)
    } else {
      existingChild
    }
    if (existingChild == null) {
      WorkflowFamily.TASK_RUNTIME.saveRecord(
        unitOfWork.workflowStates,
        childUpdated.toRecord().copy(issueKey = normalizeRequiredIssueKey(state.manifest.issueKey)),
      )
      val identity = expectedIdentity
      FeatureTaskExecutionIdentityPolicy.validate(identity)
      unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(identity)
    }
    val refreshedParent =
      WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentUpdated.workflowId) ?: parentUpdated
    return SavedGoalChildWorkflow(
      state = GoalRunnerManifestState(
        parentWorkflowId = refreshedParent.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = refreshedParent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
        controlState = unitOfWork.goalRunnerControls.controlState(refreshedParent.workflowId),
      ),
      projectionArtifactsJson = refreshedParent.artifactsJson,
    )
  }

  private fun expectedChildIdentity(setup: GoalRunnerChildWorkflowSetup) = FeatureTaskExecutionIdentity(
    workflowId = setup.workflowId,
    normalizedIssueKey = setup.normalizedIssueKey,
    repositoryIdentity = setup.repositoryIdentity,
    governedSpecPath = setup.governedSpecPath,
    mode = FeatureTaskWorkflowMode.RUNTIME,
    routeScope = FeatureTaskRouteScope.GOAL_CHILD,
  )

  private fun requireConsistentChildSetup(state: GoalRunnerManifestState, setup: GoalRunnerChildWorkflowSetup) {
    val request = setup.planningHydration ?: return
    val selected = state.manifest.subtasks.singleOrNull { it.id == setup.subtaskId }
    val failures = listOfNotNull(
      "parent workflow".takeIf { request.identity.parentGoalWorkflowId != state.parentWorkflowId },
      "issue key".takeIf {
        request.identity.normalizedIssueKey != setup.normalizedIssueKey ||
          setup.normalizedIssueKey != normalizeRequiredIssueKey(state.manifest.issueKey)
      },
      "repository".takeIf { request.identity.repositoryIdentity != setup.repositoryIdentity },
      "subtask".takeIf { request.descriptor.subtaskId != setup.subtaskId },
      "governed spec".takeIf { request.descriptor.governedSubSpecPath != setup.governedSpecPath },
      "manifest subtask".takeIf {
        selected == null ||
          canonicalGovernedSpecPath(selected.specPath, setup.repositoryIdentity) != setup.governedSpecPath
      },
    )
    if (failures.isNotEmpty()) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        state.parentWorkflowId,
        setup.subtaskId,
        "hydration ${failures.joinToString()} does not match child setup",
      )
    }
  }

  private fun canonicalGovernedSpecPath(specPath: String, repositoryIdentity: String): String {
    val repository = Path.of(repositoryIdentity.removePrefix("repo-root-realpath-v1:"))
    val lexical = Path.of(specPath).let { if (it.isAbsolute) it else repository.resolve(it) }
      .toAbsolutePath().normalize()
    val resolved = runCatching { lexical.toRealPath() }.getOrElse { lexical }
    return runCatching { repository.relativize(resolved).joinToString("/") }.getOrElse { specPath }
  }

  private fun requireMatchingGoalContinuation(
    existing: WorkflowStateSnapshot,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ) {
    val continuation = decodeArtifacts(existing.artifactsJson)[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]
      as? Map<*, *>
    val matches = continuation?.get("issue_key") == state.manifest.issueKey &&
      (continuation["subtask_id"] as? Number)?.toInt() == setup.subtaskId &&
      continuation["parent_workflow_id"] == state.parentWorkflowId &&
      continuation["goal_branch"] == setup.goalBranch && continuation["suppress_pr"] == true
    if (!matches) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        state.parentWorkflowId,
        setup.subtaskId,
        "existing child goal continuation conflicts with child setup",
      )
    }
  }

  private fun updateParentForChildWorkflow(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
  ): WorkflowStateSnapshot {
    val existingRecord = unitOfWork.workflowStates.getFeatureTaskWorkflow(state.parentWorkflowId)
      ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
        state.manifest.issueKey,
        decompositionManifestValidator,
      )
      ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
    existingRecord.requireRuntimeModeForEngineWrite()
    val existingParent = existingRecord.toSnapshot()
    migrateLegacyGoalRunnerControls(unitOfWork, existingParent)
    val parentUpdated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      existingParent,
      WorkflowUpdateInput(
        workflowStatus = existingParent.workflowStatus,
        currentStepId = existingParent.currentStepId,
        stepUpdates = null,
        // Child planning, implementation, audit, review, diagnostics, and raw output stay on
        // the child workflow. The parent projection carries only its manifest metadata and the
        // terminal subtask fields represented by {status, commit_sha, workflow_id}.
        artifactsPatch = parentProjection.artifacts(
          mergeConcurrentGoalProgress(
            existingParent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
            state.manifest,
          ),
          existingParent.artifactsJson,
        ),
        sessionId = existingParent.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      parentUpdated.toRecord().copy(issueKey = normalizeRequiredIssueKey(state.manifest.issueKey)),
    )
    return parentUpdated
  }

  private fun openGoalChildWorkflow(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    parentWorkflowId: String,
  ): WorkflowStateSnapshot {
    val openedChild = engine.openRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      setup.workflowId,
      "${WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix}-${state.manifest.issueKey}",
      WorkflowFamily.TASK_RUNTIME.definition.defaultInitialStepId,
    )
    val hydration = planningHydrator.hydrate(
      unitOfWork,
      setup,
      requireNotNull(setup.planningHydration) {
        "Prepared goal child '${setup.subtaskId}' requires planning hydration."
      },
    )
    return engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      openedChild,
      WorkflowUpdateInput(
        workflowStatus = openedChild.workflowStatus,
        currentStepId = hydration.currentStepId,
        stepUpdates = hydration.stepUpdates,
        artifactsPatch = childWorkflowArtifacts(state, setup, parentWorkflowId) + hydration.artifacts,
        sessionId = openedChild.sessionId.orEmpty(),
      ),
    )
  }

  private fun childWorkflowArtifacts(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    parentWorkflowId: String,
  ): Map<String, Any?> = mapOf(
    FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = state.manifest.issueKey,
      subtaskId = setup.subtaskId,
      suppressPr = true,
      goalBranch = setup.goalBranch,
      parentWorkflowId = parentWorkflowId,
      codeReviewMode = setup.reviewPolicy.codeReviewMode,
      validationDepth = ValidationDepth.FULL,
      qualityGateSelection = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, setup.subtaskId),
      parallelReviewAgent = setup.reviewPolicy.parallelReviewAgent,
      subtaskName = state.manifest.subtasks.firstOrNull { it.id == setup.subtaskId }?.name?.takeIf(String::isNotBlank),
    ).toArtifactMap(),
    GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to GoalSubtaskReviewState.initial(
      reviewBaseSha = setup.reviewBaseline.reviewBaseSha,
      baselineUntrackedPaths = setup.reviewBaseline.baselineUntrackedPaths,
      codeReviewMode = setup.reviewPolicy.codeReviewMode,
    ).toArtifactMap(),
    "install_sync_result" to mapOf(
      "status" to "deferred",
      "reason" to
        "goal-continuation defers installer, uninstall, and install-sync flows until the parent goal exits; " +
        "deferred install sync must not block subtask completion",
    ),
  )

  override fun reviewMode(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): skillbill.workflow.model.CodeReviewExecutionMode? = database.read(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)?.codeReviewMode
      ?: featureTaskRecordForLegacyControls(unitOfWork.workflowStates, parentWorkflowId)
        ?.let { record -> reviewPolicyFromLegacyArtifacts(decodeArtifacts(record.artifactsJson))?.codeReviewMode }
  }

  override fun persistReviewMode(
    parentWorkflowId: String,
    mode: skillbill.workflow.model.CodeReviewExecutionMode,
    dbPathOverride: String?,
  ): skillbill.workflow.model.CodeReviewExecutionMode = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    migrateLegacyGoalRunnerControls(unitOfWork, record)
    val existing = unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)?.codeReviewMode
    if (existing != null) {
      parentProjection.rewrite(unitOfWork, record)
      existing
    } else {
      unitOfWork.goalRunnerControls.persistReviewPolicy(
        parentWorkflowId,
        GoalRunnerReviewPolicy(codeReviewMode = mode),
      )
      parentProjection.rewrite(unitOfWork, record)
      mode
    }
  }

  override fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerReviewPolicy? =
    database.read(dbPathOverride) { unitOfWork ->
      unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)
        ?: featureTaskRecordForLegacyControls(unitOfWork.workflowStates, parentWorkflowId)
          ?.let { record -> reviewPolicyFromLegacyArtifacts(decodeArtifacts(record.artifactsJson)) }
    }

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String?,
  ): GoalRunnerReviewPolicy = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    migrateLegacyGoalRunnerControls(unitOfWork, record)
    val existing = unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)
    if (existing == policy) {
      parentProjection.rewrite(unitOfWork, record)
      existing
    } else {
      unitOfWork.goalRunnerControls.persistReviewPolicy(parentWorkflowId, policy)
      parentProjection.rewrite(unitOfWork, record)
      policy
    }
  }

  override fun outOfBandAcceptances(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerOutOfBandAcceptance> = database.read(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.outOfBandAcceptances(parentWorkflowId).ifEmpty {
      featureTaskRecordForLegacyControls(unitOfWork.workflowStates, parentWorkflowId)
        ?.let { record -> outOfBandAcceptancesFromLegacyArtifacts(decodeArtifacts(record.artifactsJson)) }
        .orEmpty()
    }
  }

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
    dbPathOverride: String?,
  ): GoalRunnerOutOfBandAcceptance = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    migrateLegacyGoalRunnerControls(unitOfWork, record)
    unitOfWork.goalRunnerControls.persistOutOfBandAcceptance(parentWorkflowId, acceptance)
    parentProjection.rewrite(unitOfWork, record)
    acceptance
  }

  private fun saveWorkflowProjection(state: GoalRunnerManifestState, dbPathOverride: String?): SavedManifestProjection {
    return database.transaction(dbPathOverride) { unitOfWork -> saveWorkflowProjectionInTransaction(unitOfWork, state) }
  }

  private fun saveWorkflowProjectionInTransaction(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    clearOutOfBandAcceptances: Boolean = false,
    mergeConcurrentProgress: Boolean = true,
  ): SavedManifestProjection {
    val existingRecord = unitOfWork.workflowStates.getFeatureTaskWorkflow(state.parentWorkflowId)
      ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
        state.manifest.issueKey,
        decompositionManifestValidator,
      )
      ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
    existingRecord.requireRuntimeModeForEngineWrite()
    val existing = existingRecord.toSnapshot()
    val existingSnapshot = existing
    migrateLegacyGoalRunnerControls(unitOfWork, existingSnapshot)
    if (clearOutOfBandAcceptances) {
      unitOfWork.goalRunnerControls.clearOutOfBandAcceptances(existingSnapshot.workflowId)
      unitOfWork.goalRunnerControls.clearControlState(existingSnapshot.workflowId)
    }
    val manifest = if (mergeConcurrentProgress) {
      mergeConcurrentGoalProgress(
        existingSnapshot.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
        state.manifest,
      )
    } else {
      state.manifest
    }
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      existingSnapshot,
      WorkflowUpdateInput(
        workflowStatus = existingSnapshot.workflowStatus,
        currentStepId = existingSnapshot.currentStepId,
        stepUpdates = null,
        artifactsPatch = parentProjection.artifacts(manifest, existingSnapshot.artifactsJson),
        sessionId = existingSnapshot.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
    val refreshed = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, updated.workflowId) ?: updated
    return SavedManifestProjection(
      state = GoalRunnerManifestState(
        parentWorkflowId = refreshed.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = refreshed.decompositionRuntime(decompositionManifestValidator) ?: manifest,
        controlState = unitOfWork.goalRunnerControls.controlState(refreshed.workflowId),
        repoRoot = state.repoRoot,
      ),
      projectionArtifactsJson = refreshed.artifactsJson,
    )
  }

  private fun loadFromWorkflowStore(
    issueKey: String,
    dbPathOverride: String?,
    currentProjectedManifest: DecompositionManifest? = null,
  ): GoalRunnerManifestState? = database.read(dbPathOverride) { unitOfWork ->
    loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest)
  }

  private fun loadFromWorkflowStoreIfPresent(
    issueKey: String,
    dbPathOverride: String?,
    currentProjectedManifest: DecompositionManifest? = null,
  ): GoalRunnerManifestState? = database.readIfPresent(dbPathOverride) { unitOfWork ->
    loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest)
  }

  private fun loadFromWorkflowUnitOfWork(
    unitOfWork: UnitOfWork,
    issueKey: String,
    currentProjectedManifest: DecompositionManifest?,
  ): GoalRunnerManifestState? {
    val record = unitOfWork.workflowStates.findDecomposedParentWorkflow(
      issueKey,
      decompositionManifestValidator,
      currentProjectedManifest,
    ) ?: return null
    val snapshot = record.toSnapshot()
    val manifest = snapshot.decompositionRuntime(decompositionManifestValidator) ?: return null
    return GoalRunnerManifestState(
      parentWorkflowId = snapshot.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      manifest = manifest,
      controlState = unitOfWork.goalRunnerControls.controlState(snapshot.workflowId),
    )
  }

  private fun importFromManifestProjection(
    manifest: DecompositionManifest,
    dbPathOverride: String?,
  ): GoalRunnerManifestState? {
    return database.transaction(dbPathOverride) { unitOfWork ->
      // The caller's discovery read runs in its own transaction, so a concurrent resume can insert
      // the parent between that read and this write. Repeat the lookup inside the write transaction
      // and reuse the row rather than minting a second parent id for the same issue key. The
      // corrupt-fallback path mirrors bootstrapParentWorkflowFromManifest so both entry points
      // reclaim the same row instead of minting a divergent parent id.
      val existingRecord = unitOfWork.workflowStates.findDecomposedParentOrCorruptFallback(
        manifest.issueKey,
        decompositionManifestValidator,
        manifest,
      )
      existingRecord?.requireRuntimeModeForEngineWrite()
      val existing = existingRecord?.toSnapshot()
      existing?.let { migrateLegacyGoalRunnerControls(unitOfWork, it) }
      val base = existing ?: engine.openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        generateWorkflowId(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix),
        WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix,
        "plan",
      )
      val imported = engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        base,
        WorkflowUpdateInput(
          workflowStatus = "paused",
          currentStepId = "plan",
          stepUpdates = if (existing != null) {
            null
          } else {
            listOf(
              mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
              mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
            )
          },
          artifactsPatch = parentProjection.artifacts(manifest, base.artifactsJson),
          sessionId = base.sessionId.orEmpty(),
          replaceArtifacts = true,
        ),
      )
      WorkflowFamily.TASK_RUNTIME.saveRecord(
        unitOfWork.workflowStates,
        imported.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
      )
      val saved = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, imported.workflowId) ?: imported
      GoalRunnerManifestState(
        parentWorkflowId = saved.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = saved.decompositionRuntime(decompositionManifestValidator) ?: manifest,
        controlState = unitOfWork.goalRunnerControls.controlState(saved.workflowId),
      )
    }
  }

  private fun findProjectedManifest(repoRoot: Path, issueKey: String, recoverPending: Boolean = true) =
    resolveDecompositionManifest(
      repoRoot = repoRoot,
      issueKey = issueKey,
      fileStore = decompositionManifestFileStore,
      validator = decompositionManifestValidator,
      recoverPending = recoverPending,
    )
}

private class GoalRunnerControlCoordinator(
  private val database: DatabaseSessionFactory,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val clock: Clock,
  private val saveProjection: (UnitOfWork, GoalRunnerManifestState) -> SavedManifestProjection,
) {
  fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    database.read(dbPathOverride) { unitOfWork -> unitOfWork.goalRunnerControls.controlState(parentWorkflowId) }

  fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? =
    database.read(dbPathOverride) { unitOfWork -> unitOfWork.goalRunnerControls.executionLease(parentWorkflowId) }

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken)
  }

  fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.heartbeatExecutionLease(parentWorkflowId, lease)
  }

  fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.releaseExecutionLease(parentWorkflowId, ownerToken, generation)
  }

  fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerLaunchAuthorization = database.transaction(dbPathOverride) { unitOfWork ->
    require(subtaskId > 0) { "subtaskId must be positive." }
    val existing = requireParent(unitOfWork, state.parentWorkflowId)
    val controls = unitOfWork.goalRunnerControls.controlState(existing.workflowId)
    val manifest = existing.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
    GoalRunnerLaunchAuthorization(
      authorized = !controls.requiresPauseBoundary(manifest),
      controlState = controls,
      spawnAuthorization = spawnAuthorization(state, dbPathOverride),
    )
  }

  fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerControlState = database.transaction(dbPathOverride) { unitOfWork ->
    require(subtaskId > 0) { "stop-after subtask id must be positive." }
    val parent = requireParent(unitOfWork, parentWorkflowId)
    val manifest = parent.decompositionRuntime(decompositionManifestValidator)
      ?: error("Goal parent '$parentWorkflowId' has no decomposition manifest.")
    require(manifest.subtasks.any { it.id == subtaskId }) {
      "Goal parent '$parentWorkflowId' has no subtask '$subtaskId'."
    }
    val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
    require(existing.stopAfterSubtaskId == null || existing.stopAfterSubtaskId == subtaskId) {
      "Goal parent '$parentWorkflowId' already has stop-after subtask ${existing.stopAfterSubtaskId}."
    }
    existing.stopAfterSubtaskId?.let { existing }
      ?: unitOfWork.goalRunnerControls.persistControlState(
        parentWorkflowId,
        existing.copy(stopAfterSubtaskId = subtaskId),
      )
  }

  /**
   * One bounded transaction that flips the goal straight to paused. No status inspection, log read,
   * file read, or child supervision: the stop verb and the shutdown hook both call this on paths
   * where anything more is unsafe or unaffordable.
   */
  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
    dbPathOverride: String?,
  ): GoalRunnerControlState? = database.transaction(dbPathOverride) { unitOfWork ->
    val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: return@transaction null
    migrateLegacyGoalRunnerControls(unitOfWork, parent)
    val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
    // Reason precedence: an already-paused goal keeps the more specific reason the stop verb wrote.
    if (existing.paused && !overwriteExistingReason) {
      return@transaction existing
    }
    unitOfWork.goalRunnerControls.persistControlState(
      parentWorkflowId,
      existing.copy(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = reason,
        pausedAt = pausedAt,
      ),
    )
  }

  fun requestPause(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState? =
    database.transaction(dbPathOverride) { unitOfWork ->
      WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)?.let { parent ->
        migrateLegacyGoalRunnerControls(unitOfWork, parent)
        persistPauseRequest(unitOfWork, parentWorkflowId)
      }
    }

  fun requestPauseByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerPausePersistenceResult? = database.transaction(dbPathOverride) { unitOfWork ->
    val parent = unitOfWork.workflowStates.findDecomposedParentWorkflow(
      issueKey,
      decompositionManifestValidator,
    ) ?: return@transaction null
    migrateLegacyGoalRunnerControls(unitOfWork, parent.toSnapshot())
    val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    if (repoRoot != null) {
      val identity = goalRepositoryIdentity(repoRoot)
      require(existing.repositoryIdentity == null || existing.repositoryIdentity == identity) {
        "Goal parent '${parent.workflowId}' belongs to another repository."
      }
      if (existing.repositoryIdentity == null) {
        unitOfWork.goalRunnerControls.persistControlState(
          parent.workflowId,
          existing.copy(repositoryIdentity = identity),
        )
      }
    }
    GoalRunnerPausePersistenceResult(parent.workflowId, persistPauseRequest(unitOfWork, parent.workflowId))
  }

  fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String?,
  ): GoalRunnerControlState = database.transaction(dbPathOverride) { unitOfWork ->
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity is required." }
    val parent = requireParent(unitOfWork, parentWorkflowId)
    val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    require(existing.repositoryIdentity == null || existing.repositoryIdentity == repositoryIdentity) {
      "Goal parent '$parentWorkflowId' belongs to another repository."
    }
    if (existing.repositoryIdentity == repositoryIdentity) {
      existing
    } else {
      unitOfWork.goalRunnerControls.persistControlState(
        parent.workflowId,
        existing.copy(repositoryIdentity = repositoryIdentity),
      )
    }
  }

  fun resume(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerManifestState? =
    database.transaction(dbPathOverride) { unitOfWork ->
      val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
        ?: return@transaction null
      migrateLegacyGoalRunnerControls(unitOfWork, parent)
      val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
      val resumed = if (existing.paused || existing.pauseRequested) {
        unitOfWork.goalRunnerControls.persistControlState(
          parentWorkflowId,
          existing.copy(
            pauseRequested = false,
            pauseConsumed = false,
            paused = false,
            pauseReason = null,
            pausedAt = null,
          ),
        )
      } else {
        existing
      }
      parent.decompositionRuntime(decompositionManifestValidator)?.let { manifest ->
        GoalRunnerManifestState(
          parentWorkflowId = parent.workflowId,
          dbPath = unitOfWork.dbPath.toString(),
          manifest = manifest,
          controlState = resumed,
        )
      }
    }

  fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    database.transaction(dbPathOverride) { unitOfWork ->
      val parent = requireParent(unitOfWork, state.parentWorkflowId)
      val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
      val authoritativeManifest = parent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
      val authoritativeState = state.copy(manifest = authoritativeManifest)
      val targetReached = controls.targetReached(authoritativeState)
      val pausedControls = if (controls.requiresPauseBoundary(authoritativeManifest)) {
        controls.pauseAtOperatorBoundary(clock.instant().toString(), targetReached)
      } else {
        controls
      }
      val saved = saveProjection(unitOfWork, authoritativeState)
      if (pausedControls != controls) {
        unitOfWork.goalRunnerControls.persistControlState(parent.workflowId, pausedControls)
      }
      saved.state.copy(controlState = pausedControls)
    }

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerCompletionPersistenceResult = database.transaction(dbPathOverride) { unitOfWork ->
    val parent = requireParent(unitOfWork, state.parentWorkflowId)
    val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    val persistedManifest = parent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
    val authoritativeManifest = mergeConcurrentGoalProgress(persistedManifest, state.manifest)
    val authoritativeState = state.copy(manifest = authoritativeManifest)
    val targetReached = controls.stopAfterSubtaskId == subtaskId && !controls.stopAfterConsumed
    val operatorRequested = controls.pauseRequested && !controls.pauseConsumed
    val shouldPause = controls.requiresPauseBoundary(authoritativeManifest) || targetReached || operatorRequested
    val nextControls =
      if (shouldPause) controls.pauseAtOperatorBoundary(clock.instant().toString(), targetReached) else controls
    val saved = saveProjection(unitOfWork, authoritativeState)
    if (nextControls != controls) {
      unitOfWork.goalRunnerControls.persistControlState(parent.workflowId, nextControls)
    }
    GoalRunnerCompletionPersistenceResult(
      state = saved.state.copy(controlState = nextControls),
      paused = shouldPause,
    )
  }

  private fun requireParent(unitOfWork: UnitOfWork, parentWorkflowId: String): WorkflowStateSnapshot {
    val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Unknown decomposed parent workflow '$parentWorkflowId'.")
    migrateLegacyGoalRunnerControls(unitOfWork, parent)
    return parent
  }

  private fun spawnAuthorization(state: GoalRunnerManifestState, dbPathOverride: String?): AgentRunSpawnAuthorization =
    object : AgentRunSpawnAuthorization {
      override fun <T> withAuthorization(spawn: () -> T): T = database.transaction(dbPathOverride) { unitOfWork ->
        val parent = requireParent(unitOfWork, state.parentWorkflowId)
        val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
        val manifest = parent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest
        if (controls.requiresPauseBoundary(manifest)) {
          throw skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorizationDeniedException(controls)
        }
        spawn()
      }
    }

  fun planningSpawnAuthorization(parentWorkflowId: String, dbPathOverride: String?): AgentRunSpawnAuthorization =
    object : AgentRunSpawnAuthorization {
      override fun <T> withAuthorization(spawn: () -> T): T = database.transaction(dbPathOverride) { unitOfWork ->
        val parent = requireParent(unitOfWork, parentWorkflowId)
        val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
        val manifest = parent.decompositionRuntime(decompositionManifestValidator)
          ?: error("Goal parent '$parentWorkflowId' has no decomposition manifest.")
        if (controls.requiresPauseBoundary(manifest)) {
          throw skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorizationDeniedException(controls)
        }
        spawn()
      }
    }

  private fun persistPauseRequest(unitOfWork: UnitOfWork, parentWorkflowId: String): GoalRunnerControlState {
    val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
    return if (existing.paused || existing.pauseRequested) {
      existing
    } else {
      unitOfWork.goalRunnerControls.persistControlState(
        parentWorkflowId,
        existing.copy(
          pauseRequested = true,
          pauseConsumed = false,
          pauseReason = GOAL_PAUSE_REASON_OPERATOR_REQUEST,
        ),
      )
    }
  }

  private fun GoalRunnerControlState.targetReached(state: GoalRunnerManifestState): Boolean =
    stopAfterSubtaskId?.let { targetId ->
      state.manifest.subtasks.any { it.id == targetId && it.status == "complete" }
    } == true && !stopAfterConsumed
}

private class GoalParentProjectionWriter(
  private val engine: WorkflowEngine,
  private val validator: DecompositionManifestValidator,
) {
  fun artifacts(manifest: DecompositionManifest, existingArtifactsJson: String? = null): Map<String, Any?> =
    LinkedHashMap(
      existingArtifactsJson
        ?.takeIf(String::isNotBlank)
        ?.let(::decodeArtifacts)
        .orEmpty(),
    ).apply {
      remove(GOAL_REVIEW_POLICY_ARTIFACT_KEY)
      remove(GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY)
      put(
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
        encodeDecompositionManifestMap(manifest, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
      )
    }

  fun rewrite(unitOfWork: UnitOfWork, existing: WorkflowStateSnapshot) {
    val manifest = existing.decompositionRuntime(validator)
      ?: error("Goal parent workflow '${existing.workflowId}' has no decomposition manifest.")
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      existing,
      WorkflowUpdateInput(
        workflowStatus = existing.workflowStatus,
        currentStepId = existing.currentStepId,
        stepUpdates = null,
        artifactsPatch = artifacts(manifest, existing.artifactsJson),
        sessionId = existing.sessionId,
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
  }
}

private fun mergeConcurrentGoalProgress(
  persisted: DecompositionManifest,
  incoming: DecompositionManifest,
): DecompositionManifest {
  val persistedById = persisted.subtasks.associateBy { it.id }
  val mergedSubtasks = incoming.subtasks.map { candidate ->
    val current = persistedById[candidate.id]
    if (current?.status == "complete" && candidate.status != "complete") current else candidate
  }
  val merged = incoming.copy(subtasks = mergedSubtasks)
  return if (
    persisted.currentSubtaskIntent.subtaskId > 0 &&
    merged.subtasks.firstOrNull { it.id == persisted.currentSubtaskIntent.subtaskId }?.status == "complete" &&
    merged.currentSubtaskIntent.subtaskId == persisted.currentSubtaskIntent.subtaskId
  ) {
    merged.copy(currentSubtaskIntent = persisted.currentSubtaskIntent).withParentStatus()
  } else {
    merged.withParentStatus()
  }
}

/**
 * Moves legacy parent-owned controls out of workflow artifacts before a thin parent projection
 * replaces that artifact map. The operation is safe to repeat because existing durable controls
 * remain authoritative and already-migrated acceptance entries are not written again.
 */
internal fun migrateLegacyGoalRunnerControls(unitOfWork: UnitOfWork, existing: WorkflowStateSnapshot) {
  val artifacts = decodeArtifacts(existing.artifactsJson)
  if (unitOfWork.goalRunnerControls.reviewPolicy(existing.workflowId) == null) {
    reviewPolicyFromLegacyArtifacts(artifacts)?.let {
      unitOfWork.goalRunnerControls.persistReviewPolicy(existing.workflowId, it)
    }
  }
  val durableAcceptances = unitOfWork.goalRunnerControls.outOfBandAcceptances(existing.workflowId)
  outOfBandAcceptancesFromLegacyArtifacts(artifacts)
    .filterKeys { it !in durableAcceptances }
    .values
    .forEach { acceptance ->
      unitOfWork.goalRunnerControls.persistOutOfBandAcceptance(existing.workflowId, acceptance)
    }
}

private fun DecompositionManifest.afterIncompatibleChildDeletion(subtaskId: Int): DecompositionManifest = copy(
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "start"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id != subtaskId) {
      subtask
    } else {
      subtask.copy(
        status = "pending",
        branch = null,
        commitSha = null,
        workflowId = null,
        blockedReason = null,
        lastResumableStep = null,
      )
    }
  },
).withParentStatus()

// Terminal subtasks keep their child workflow: a scoped replan preserves every completed subtask's
// commit_sha and workflow_id, and a finished child is never re-hydrated from the discarded plan.
private fun deleteStaleReplanChildren(
  unitOfWork: UnitOfWork,
  state: GoalRunnerManifestState,
  subtaskIds: List<Int>,
): List<Int> = subtaskIds.distinct().sorted().filter { id ->
  val subtask = state.manifest.subtasks.singleOrNull { it.id == id }
  val childWorkflowId = subtask?.workflowId?.takeIf(String::isNotBlank)
  if (subtask == null || childWorkflowId == null || subtask.status in setOf("complete", "skipped")) {
    false
  } else {
    unitOfWork.workflowStates.deleteGoalChildWorkflow(
      state.parentWorkflowId,
      id,
      childWorkflowId,
      GoalChildWorkflowDeletionScope.TERMINAL_OR_RESUMABLE,
    ) == 1
  }
}

// Unlike afterIncompatibleChildDeletion this preserves the caller's currentSubtaskIntent, which the
// replan path has already retargeted at the replanned subtask.
private fun DecompositionManifest.afterReplanChildDeletion(subtaskIds: List<Int>): DecompositionManifest {
  if (subtaskIds.isEmpty()) return this
  return copy(
    subtasks = subtasks.map { subtask ->
      if (subtask.id !in subtaskIds) {
        subtask
      } else {
        subtask.copy(
          status = "pending",
          branch = null,
          commitSha = null,
          workflowId = null,
          blockedReason = null,
          lastResumableStep = null,
        )
      }
    },
  ).withParentStatus()
}

private fun reviewPolicyFromLegacyArtifacts(artifacts: Map<String, Any?>): GoalRunnerReviewPolicy? {
  val raw = artifacts[GOAL_REVIEW_POLICY_ARTIFACT_KEY] ?: return null
  val policy = JsonSupport.anyToStringAnyMap(raw)
    ?: error("Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' must be a map.")
  val allowedKeys = setOf("code_review_mode", "parallel_review_agent", "agent_addon_selection")
  policy.keys.forEach { key ->
    require(key in allowedKeys) {
      "Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' has unsupported field '$key'."
    }
  }
  val mode = policy["code_review_mode"] as? String
    ?: error("Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' is missing code_review_mode.")
  val codeReviewMode = try {
    skillbill.workflow.model.CodeReviewExecutionMode.fromWire(mode)
  } catch (error: IllegalArgumentException) {
    throw IllegalStateException("Goal review policy artifact has invalid code_review_mode '$mode'.", error)
  }
  val parallelReviewAgent = when (val value = policy["parallel_review_agent"]) {
    null -> null
    is String -> value.takeIf(String::isNotBlank)
      ?: error("Goal review policy artifact has a blank parallel_review_agent.")
    else -> error("Goal review policy artifact parallel_review_agent must be a string.")
  }
  val agentAddonSelection = decodeGoalAgentAddonSelection(policy["agent_addon_selection"])
  return GoalRunnerReviewPolicy(codeReviewMode, parallelReviewAgent, agentAddonSelection)
}

private fun outOfBandAcceptancesFromLegacyArtifacts(
  artifacts: Map<String, Any?>,
): Map<Int, GoalRunnerOutOfBandAcceptance> {
  val raw = artifacts[GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY] ?: return emptyMap()
  val entries = raw as? List<*>
    ?: error("Goal acceptance artifact '$GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY' must be a list.")
  return entries.associate { element ->
    val entry = JsonSupport.anyToStringAnyMap(element)
      ?: error("Goal acceptance artifact '$GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY' entries must be maps.")
    val acceptance = GoalRunnerOutOfBandAcceptance(
      subtaskId = (entry["subtask_id"] as? Number)?.toInt()
        ?: error("Goal acceptance artifact entry is missing a numeric subtask_id."),
      commitSha = entry["commit_sha"] as? String
        ?: error("Goal acceptance artifact entry is missing commit_sha."),
      reason = entry["reason"] as? String
        ?: error("Goal acceptance artifact entry is missing reason."),
      acceptedAt = entry["accepted_at"] as? String
        ?: error("Goal acceptance artifact entry is missing accepted_at."),
    )
    acceptance.subtaskId to acceptance
  }
}

private fun GoalRunnerControlState.pauseAtOperatorBoundary(
  pausedAtNow: String,
  targetReached: Boolean = false,
): GoalRunnerControlState = when {
  // Already paused: the original pause instant is the one that matters, so it is never restamped.
  paused -> copy(stopAfterConsumed = stopAfterConsumed || targetReached)
  pauseRequested -> copy(
    pauseConsumed = true,
    paused = true,
    pauseReason = pauseReason ?: GOAL_PAUSE_REASON_OPERATOR_REQUEST,
    pausedAt = pausedAtNow,
    stopAfterConsumed = stopAfterConsumed || targetReached,
  )
  targetReached -> copy(
    paused = true,
    pauseReason = GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK,
    pausedAt = pausedAtNow,
    stopAfterConsumed = true,
  )
  else -> this
}

private fun decodeGoalAgentAddonSelection(raw: Any?): skillbill.agentaddon.model.AgentAddonSelection {
  val values = raw ?: return skillbill.agentaddon.model.AgentAddonSelection()
  val entries = values as? List<*> ?: error("Goal review policy agent_addon_selection must be a list.")
  return skillbill.agentaddon.model.AgentAddonSelection(
    entries.mapIndexed { index, value ->
      val entry = JsonSupport.anyToStringAnyMap(value)
        ?: error("Goal review policy agent_addon_selection entry $index must be a map.")
      check(entry.keys == setOf("slug", "source_identity", "content_sha256")) {
        "Goal review policy agent_addon_selection entry $index has invalid fields."
      }
      skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry(
        entry["slug"] as? String ?: error("Goal review policy add-on entry $index is missing slug."),
        entry["source_identity"] as? String
          ?: error("Goal review policy add-on entry $index is missing source_identity."),
        entry["content_sha256"] as? String
          ?: error("Goal review policy add-on entry $index is missing content_sha256."),
      )
    },
  )
}

private data class SavedManifestProjection(
  val state: GoalRunnerManifestState,
  val projectionArtifactsJson: String,
)

private fun DecompositionManifest.isCompleteGoalProjection(): Boolean =
  status == "complete" && currentSubtaskIntent.action == "complete" && subtasks.all { subtask ->
    subtask.status in setOf("complete", "skipped") &&
      (subtask.status == "skipped" || !subtask.commitSha.isNullOrBlank())
  }

@Inject
@Suppress("LongParameterList", "LargeClass") // one cohesive goal-runner outcome store; bundling would only hide it
class WorkflowGoalRunnerOutcomeStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalObservabilityEventValidator: GoalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
  private val goalProgressEventValidator: GoalProgressEventValidator = NoopGoalProgressEventValidator,
  // Ground-truth git read to recover the terminal commit SHA when an agent completes
  // commit_push under suppress_pr but omits the SHA. No-op default keeps artifact-only behavior.
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator = ReviewRawOutputFallbackValidator,
  // Injectable liveness probe for goal-parent crash reconciliation (AC-005). The no-op default never
  // confirms a process dead, so a seam wired without a real supervisor never reconciles.
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  private val decompositionManifestValidator: DecompositionManifestValidator? = null,
  private val decompositionManifestFileStore: DecompositionManifestFileStore =
    UnavailableDecompositionManifestFileStore,
) : GoalRunnerWorkflowOutcomeStore, GoalRunnerAttemptLedgerStore, GoalRunnerChildRepairStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  private val childRepair = GoalRunnerChildRepairOperations(engine, gitOperations, decompositionManifestValidator)

  override fun diagnoseChildWedges(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    subtasks: List<DecompositionSubtask>,
    repoRoot: Path,
    dbPathOverride: String?,
  ): skillbill.application.model.GoalRunnerChildWedgeDiagnosis = database.read(dbPathOverride) { unitOfWork ->
    childRepair.diagnose(
      workflowStates = unitOfWork.workflowStates,
      workflowId = workflowId,
      issueKey = issueKey,
      subtaskId = subtaskId,
      repoRoot = repoRoot,
    )
  }

  override fun applyChildWedgeRepairs(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    wedgeClasses: List<skillbill.application.model.GoalRunnerWedgeClass>,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerChildRepairApplyResult {
    val result = database.transaction(dbPathOverride) { unitOfWork ->
      childRepair.apply(
        unitOfWork = unitOfWork,
        workflowId = workflowId,
        issueKey = issueKey,
        subtaskId = subtaskId,
        wedgeClasses = wedgeClasses,
        repoRoot = repoRoot,
      )
    }
    result.manifestProjectionArtifactsJson?.let { artifactsJson ->
      val validator = decompositionManifestValidator ?: return@let
      checkNotNull(
        DecompositionManifestWriter.writeProjectionFromWorkflowState(
          repoRoot = repoRoot,
          artifactsJson = artifactsJson,
          validator = validator,
          fileStore = decompositionManifestFileStore,
        ),
      ) {
        "Goal repair reopened the durable goal child but could not write its decomposition manifest projection."
      }
    }
    return result
  }

  override fun goalSubtaskReviewState(workflowId: String, dbPathOverride: String?): GoalSubtaskReviewState? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@read null
      goalReviewArtifacts(decodeArtifacts(record.artifactsJson))?.state
    }

  override fun unemittedGoalReviewPasses(
    workflowId: String,
    dbPathOverride: String?,
  ): List<GoalSubtaskReviewPassResult> = database.read(dbPathOverride) { unitOfWork ->
    val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@read emptyList()
    val artifacts = decodeArtifacts(record.artifactsJson)
    // SKILL-175: the goal runner initializes goal-subtask review state when IT opens the child. A
    // child hydrated through the continuation carries `goal_continuation` but no review state yet —
    // it has no review passes to emit. Skipping here (rather than letting the decoder's
    // "goal_continuation implies review state" invariant reject the row) mirrors the prose era,
    // where continuation children were never read by this RUNTIME-only emission path.
    if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY !in artifacts) return@read emptyList()
    val review = goalReviewArtifacts(artifacts) ?: return@read emptyList()
    validatedGoalReviewPasses(review, phaseOutputValidator, unitOfWork)
      .drop(review.state.emittedPassCount)
  }

  override fun acknowledgeGoalReviewPass(workflowId: String, passNumber: Int, dbPathOverride: String?): Boolean =
    database.transaction(dbPathOverride) { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val review = goalReviewArtifacts(artifacts) ?: return@transaction false
      val state = review.state
      validatedGoalReviewPasses(review, phaseOutputValidator, unitOfWork)
      if (passNumber != state.emittedPassCount + 1 || passNumber > state.completedPassCount) {
        return@transaction false
      }
      val updated = engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = mapOf(
            GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.acknowledgeSummariesThrough(passNumber).toArtifactMap(),
          ),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
      true
    }

  @Suppress("LongMethod") // SKILL-176: one authoritative reconcile pass; splitting would obscure ordering invariants
  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> = database.transaction(dbPathOverride) { unitOfWork ->
    val normalizedIssueKey = issueKey.trim()
    val activeSet = activeWorkflowIds.map(String::trim).filter(String::isNotBlank).toSet()
    // SKILL-176: displace stale blocked goal_continuation_outcome rows before authority selection and
    // the stale-running markBlocked pass, so a just-released child cannot be re-blocked from its own
    // superseded artifact on this or the next resume.
    loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot = null)
      .forEach { candidate ->
        displaceStaleBlockedContinuationOutcomeIfPresent(
          unitOfWork.workflowStates,
          candidate.snapshot.workflowId,
          candidate.goalContinuation.issueKey,
          candidate.goalContinuation.subtaskId,
        )
      }
    val initialCandidates = loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
    // SKILL-68 (AC3/AC4 case 4): with a repo root, this is the manifest-workflowId-independent heal.
    // A candidate that resolved COMPLETE via a measured HEAD SHA (its artifacts carried no SHA) is
    // durably backfilled into its goal_continuation_outcome BEFORE the stale-running markBlocked pass,
    // so the now-authoritative complete-with-SHA outcome masks the blocked workflow status on the
    // re-read. persistMeasuredCompletion is idempotent: a candidate already carrying a SHA is a no-op.
    if (repoRoot != null) {
      initialCandidates
        .filter { candidate -> candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE }
        .forEach { candidate ->
          persistMeasuredCompletion(
            unitOfWork.workflowStates,
            candidate.snapshot.workflowId,
            candidate.goalContinuation.issueKey,
            candidate.goalContinuation.subtaskId,
            requireNotNull(candidate.outcome),
          )
        }
    }
    val initialAuthoritative = initialCandidates.authoritativeOutcomesBySubtask()
    initialCandidates
      .filter { candidate ->
        if (candidate.snapshot.workflowStatus != "running") {
          return@filter false
        }
        // SKILL-68: a candidate whose own resolved outcome is COMPLETE is done, not stale — never
        // stale-block it. Blocking it would also re-save its pre-backfill snapshot and revert a
        // just-measured commit SHA.
        if (candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE) {
          return@filter false
        }
        val authoritative = initialAuthoritative[candidate.goalContinuation.subtaskId]
        val inactive = candidate.snapshot.workflowId !in activeSet
        val supersededByAuthoritative = authoritative?.status == GoalRunnerTerminalStatus.COMPLETE &&
          authoritative.workflowId != candidate.snapshot.workflowId
        // SKILL-87: an empty/partial activeSet must not false-kill a still-running subtask. Under
        // requireStalenessEvidence, set membership alone is not enough — block only with positive
        // evidence the candidate is gone (no declared liveness within the staleness window).
        val staleByInactivity = if (gate.requireStalenessEvidence) {
          inactive && candidateIsStale(candidate)
        } else {
          gate.allowInactiveReconciliation && inactive
        }
        staleByInactivity || supersededByAuthoritative
      }
      .forEach { stale ->
        val authoritative = initialAuthoritative[stale.goalContinuation.subtaskId]
        val blockedReason = staleRunningReason(
          staleWorkflowId = stale.snapshot.workflowId,
          issueKey = normalizedIssueKey,
          subtaskId = stale.goalContinuation.subtaskId,
          authoritative = authoritative,
        )
        markBlocked(
          GoalRunnerBlockWrite(
            family = stale.family,
            record = stale.snapshot,
            blockedReason = blockedReason,
            lastResumableStep = stale.snapshot.currentStepId,
            workflowStates = unitOfWork.workflowStates,
            supervisionEvent = null,
          ),
        )
      }
    loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
      .authoritativeOutcomesBySubtask()
  }

  override fun authoritativeOutcomes(issueKey: String, dbPathOverride: String?): Map<Int, GoalRunnerStoredOutcome> =
    database.read(dbPathOverride) { unitOfWork ->
      loadContinuationCandidates(unitOfWork.workflowStates, issueKey.trim(), repoRoot = null)
        .authoritativeOutcomesBySubtask()
    }

  // Strictly read-only: resolve from durable artifacts only, never measuring git or
  // mutating state, so status / reconciliation reads keep their no-write contract.
  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = database.read(dbPathOverride) { unitOfWork ->
    resolveTerminalOutcome(unitOfWork.workflowStates, workflowId, issueKey, subtaskId) { null }
  }

  // Command path: recover a dropped SHA from measured HEAD and durably persist the
  // completion so status, reconciliation, and the subtask handoff all agree afterward.
  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = database.transaction(dbPathOverride) { unitOfWork ->
    // SKILL-176: evidence + supersede land on the first transactional resume that observes a stale
    // blocked outcome; read-only paths detect without writing.
    displaceStaleBlockedContinuationOutcomeIfPresent(unitOfWork.workflowStates, workflowId, issueKey, subtaskId)
    val resolved = resolveTerminalOutcome(unitOfWork.workflowStates, workflowId, issueKey, subtaskId) {
      gitOperations.headCommitSha(repoRoot).measuredCommitSha()
    } ?: return@transaction crashReconcileToResumable(unitOfWork.workflowStates, workflowId, issueKey, subtaskId)
    val recovered = resolved.let { outcome ->
      recoverResolvedCommitPushBlock(
        workflowStates = unitOfWork.workflowStates,
        identity = GoalSubtaskIdentity(workflowId, issueKey, subtaskId),
        repoRoot = repoRoot,
        outcome = outcome,
      ) ?: outcome
    }
    recovered.also { outcome ->
      persistMeasuredCompletion(unitOfWork.workflowStates, workflowId, issueKey, subtaskId, outcome)
    }
  }

  private fun recoverResolvedCommitPushBlock(
    workflowStates: WorkflowStateRepository,
    identity: GoalSubtaskIdentity,
    repoRoot: Path,
    outcome: GoalRunnerStoredOutcome,
  ): GoalRunnerStoredOutcome? = outcome
    .takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED && it.lastResumableStep == "commit_push" }
    ?.let {
      workflowFamilyFor(workflowStates, identity.workflowId)?.get(workflowStates, identity.workflowId)
    }
    ?.let { record -> goalContinuation(decodeArtifacts(record.artifactsJson)) }
    ?.takeIf { continuation ->
      continuation.issueKey == identity.issueKey && continuation.subtaskId == identity.subtaskId
    }
    ?.goalBranch
    ?.takeIf(String::isNotBlank)
    ?.takeIf { branch -> gitOperations.validateBranchBase(repoRoot, "origin/$branch", "HEAD").ok }
    ?.let { gitOperations.headCommitSha(repoRoot).measuredCommitSha() }
    ?.let { commitSha ->
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.COMPLETE,
        workflowId = identity.workflowId,
        commitSha = commitSha,
        blockedReason = null,
        lastResumableStep = "commit_push",
        suppressPr = outcome.suppressPr,
      )
    }

  override fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: Map<String, Any?>,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = database.transaction(dbPathOverride) { unitOfWork ->
    val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val terminalArtifact = missingResultPrefixTerminalOutcomeArtifact(output, issueKey, subtaskId, workflowId)
    val existingArtifacts = decodeArtifacts(record.artifactsJson)
    val artifactsPatch = linkedMapOf<String, Any?>(
      "goal_runner_missing_result_prefix_recovery" to linkedMapOf(
        "issue_key" to issueKey,
        "subtask_id" to subtaskId,
        "workflow_id" to workflowId,
        "output" to output,
      ),
    )
    if (terminalArtifact != null &&
      goalContinuationOutcome(existingArtifacts, issueKey, subtaskId, suppressPr = true) == null
    ) {
      artifactsPatch["goal_continuation_outcome"] = terminalArtifact
    }
    val updated = engine.updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = artifactsPatch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    family.save(unitOfWork.workflowStates, updated)
    // The recovered outcome above is synthesized from THIS call's own launch output, not a
    // possibly-stale prior write, so it is read directly rather than through the SKILL-176
    // corroboration gate: the workflow's own step/status signals are exactly what the missing
    // result prefix left absent or malformed, so requiring them to corroborate would defeat the
    // recovery this function exists to perform.
    val recoveredArtifacts = existingArtifacts + artifactsPatch
    val recoveredContinuation = goalContinuation(recoveredArtifacts)
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    val recovered = recoveredContinuation?.let {
      goalContinuationOutcome(recoveredArtifacts, issueKey, subtaskId, it.suppressPr)
    }?.copy(workflowId = workflowId)
    recovered ?: resolveTerminalOutcome(unitOfWork.workflowStates, workflowId, issueKey, subtaskId) { null }
  }

  private fun resolveTerminalOutcome(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    measuredCommitSha: () -> String?,
  ): GoalRunnerStoredOutcome? {
    val candidate = workflowFamilyFor(workflowStates, workflowId)
      ?.let { family -> family.get(workflowStates, workflowId)?.let { snapshot -> family to snapshot } }
    return candidate?.let { (family, snapshot) ->
      engine.snapshotView(family.definition, snapshot)
      val artifacts = decodeArtifacts(snapshot.artifactsJson)
      goalContinuation(artifacts)
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
        ?.let { continuation -> terminalOutcomeFor(snapshot, artifacts, continuation, measuredCommitSha) }
    }
  }

  // Goal-parent crash reconciliation (AC-002): a still-running goal-child row whose worker lease has
  // expired and whose process the injected supervisor confirms dead is transitioned to the resumable
  // pending state under owner_token/generation fencing, and reported RECONCILABLE so the parent
  // resumes the subtask instead of blocking with NO_TERMINAL_STORE_OUTCOME. Ambiguous liveness, a
  // live lease, or a lost fencing race all return null, leaving the existing terminal reasons intact.
  @Suppress("ReturnCount") // guard-clause reconciliation: each early null is a distinct non-reconcilable case
  private fun crashReconcileToResumable(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? {
    val ownership = workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: return null
    val row = workflowStates.getFeatureTaskRuntimeWorkflow(workflowId) ?: return null
    if (row.workflowStatus != "running") return null
    val continuation = goalContinuation(decodeArtifacts(row.artifactsJson))
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
      ?: return null
    val now = Instant.now()
    if (!runCatching { Instant.parse(ownership.expiresAt).isBefore(now) }.getOrDefault(false)) return null
    if (!FeatureTaskRuntimeCrashLiveness.isConfirmedDead(workerSupervisor.inspect(ownership))) return null
    val reconciled = workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
      workflowId = workflowId,
      ownerToken = ownership.ownerToken,
      generation = ownership.generation,
      interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
      nowInstant = now.toString(),
    )
    if (!reconciled) return null
    return GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.RECONCILABLE,
      workflowId = workflowId,
      commitSha = null,
      blockedReason = null,
      lastResumableStep = row.currentStepId.ifBlank { "preplan" },
      suppressPr = continuation.suppressPr,
    )
  }

  // Writing goal_continuation_outcome makes the verdict authoritative (read first by
  // terminalOutcomeFor), so a workflow row stranded at a later step no longer reverts the
  // subtask to blocked. Idempotent: only backfills a measured COMPLETE not yet recorded.
  private fun persistMeasuredCompletion(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    outcome: GoalRunnerStoredOutcome,
  ) {
    val recordContext = workflowFamilyFor(workflowStates, workflowId)
      ?.let { family -> family.get(workflowStates, workflowId)?.let { record -> family to record } }
      ?.takeIf { outcome.status == GoalRunnerTerminalStatus.COMPLETE && !outcome.commitSha.isNullOrBlank() }
    recordContext?.let { (family, record) ->
      val artifacts = decodeArtifacts(record.artifactsJson)
      // SKILL-68: also backfill a previously persisted complete-without-SHA outcome (not only a
      // missing one), so a row stranded by the legacy SHA-less `complete` is healed once HEAD is
      // measurable. The L393 guard keeps this one-shot: a row already carrying a SHA never re-fires.
      val existingOutcome = goalContinuationOutcome(artifacts, issueKey, subtaskId, outcome.suppressPr)
      val needsBackfill = (existingOutcome == null || existingOutcome.commitSha.isNullOrBlank()) &&
        commitShaFrom(artifacts).isNullOrBlank()
      if (needsBackfill) {
        val updated = engine.updateRecord(
          family.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch = mapOf(
              "goal_continuation_outcome" to mapOf(
                "issue_key" to issueKey,
                "subtask_id" to subtaskId,
                "status" to "complete",
                "workflow_id" to workflowId,
                "commit_sha" to outcome.commitSha,
                "last_resumable_step" to (outcome.lastResumableStep ?: "commit_push"),
              ),
            ),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
        family.save(workflowStates, updated)
      }
    }
  }

  // SKILL-176: transactional heal for a stored blocked goal_continuation_outcome whose durable row
  // no longer corroborates it. Sibling artifact key (not inside goal_continuation) so
  // rejectUnknownGoalContinuationKeys is untouched. Idempotent: a second resume finds no stored
  // blocked outcome after supersede and writes nothing.
  @Suppress("ReturnCount") // guard-clause reconciliation: each early null is a distinct non-reconcilable case
  private fun displaceStaleBlockedContinuationOutcomeIfPresent(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ) {
    val family = workflowFamilyFor(workflowStates, workflowId) ?: return
    val record = family.get(workflowStates, workflowId) ?: return
    val artifacts = decodeArtifacts(record.artifactsJson)
    val continuation = goalContinuation(artifacts)
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
      ?: return
    val stored = goalContinuationOutcome(artifacts, issueKey, subtaskId, continuation.suppressPr)
      ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
      ?: return
    val derived = derivedTerminalOutcomeFor(record, artifacts, continuation) { null }
    if (nonCompleteStoredOutcomeIsCorroborated(stored.copy(workflowId = workflowId), derived, record)) {
      return
    }
    val evidenceAlreadyPresent = artifacts[GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY] != null
    val updated = engine.updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = buildMap {
          if (!evidenceAlreadyPresent) {
            put(
              GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY,
              linkedMapOf(
                "workflow_id" to workflowId,
                "issue_key" to issueKey,
                "subtask_id" to subtaskId,
                "displaced_status" to "blocked",
                "original_blocked_reason" to stored.blockedReason,
                "failed_corroboration" to linkedMapOf(
                  "derived_status" to derived?.status?.toGoalContinuationWireStatus(),
                  "derived_blocked_reason" to derived?.blockedReason,
                  "stored_blocked_reason" to stored.blockedReason,
                ),
                "displaced_at" to Instant.now().toString(),
              ),
            )
          }
          // Supersede so subsequent reads no longer re-detect the same displacement.
          put("goal_continuation_outcome", null)
        },
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    family.save(workflowStates, updated)
  }

  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    dbPathOverride: String?,
  ): String? = database.transaction(dbPathOverride) { unitOfWork ->
    val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    markBlocked(
      GoalRunnerBlockWrite(
        family = family,
        record = record,
        blockedReason = blockedReason,
        lastResumableStep = lastResumableStep,
        workflowStates = unitOfWork.workflowStates,
        supervisionEvent = supervisionEvent,
      ),
    )
  }

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    val family = WorkflowFamily.TASK_RUNTIME
    val existing = family.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
    if (existing.workflowStatus in family.definition.terminalStatuses) {
      return@transaction false
    }
    val artifacts = decodeArtifacts(existing.artifactsJson)
    val phaseRecords = phaseRecordsFrom(artifacts)
    val blockedRecord = operatorReopenablePhaseRecord(
      phaseRecords,
      preferredPhaseId,
      existing.workflowStatus,
    ) ?: return@transaction true
    family.save(
      unitOfWork.workflowStates,
      engine.updateRecord(
        family.definition,
        existing,
        operatorBlockedPhaseReopenUpdate(blockedRecord, phaseRecords, phaseLedgerFrom(artifacts), reason),
      ),
    )
    true
  }

  private fun operatorReopenablePhaseRecord(
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    preferredPhaseId: String,
    workflowStatus: String,
  ): FeatureTaskRuntimePhaseRecord? {
    val preferred = phaseRecords[preferredPhaseId]
    return preferred?.takeIf { it.status == "blocked" }
      ?: phaseRecords.values.firstOrNull { it.status == "blocked" }
      ?: preferred?.takeIf { workflowStatus == "blocked" && it.status == "running" }
  }

  private fun operatorBlockedPhaseReopenUpdate(
    blockedRecord: FeatureTaskRuntimePhaseRecord,
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    reason: String,
  ): WorkflowUpdateInput {
    val reopened = LinkedHashMap(phaseRecords).apply {
      this[blockedRecord.phaseId] = blockedRecord.asPendingForOperatorResume()
    }
    val retryEntry = FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
      sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
      phaseId = blockedRecord.phaseId,
      attemptCount = blockedRecord.attemptCount,
      resolvedAgentId = blockedRecord.resolvedAgentId,
    )
    return WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = blockedRecord.phaseId,
      stepUpdates = listOf(
        mapOf(
          "step_id" to blockedRecord.phaseId,
          "status" to "pending",
          "attempt_count" to 0,
        ),
      ),
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          reopened.mapValues { (_, record) -> record.toArtifactMap() },
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to
          (ledger.map { it.toArtifactMap() } + retryEntry.toArtifactMap()).takeLast(
            FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
          ),
        FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY to mapOf(
          "phase_id" to blockedRecord.phaseId,
          "reason" to reason,
          "retried_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
          "previous_blocked_reason" to blockedRecord.blockedReason,
          "previous_blocked_record" to blockedRecord.toArtifactMap(),
        ),
      ),
      sessionId = "",
    )
  }

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    database.read(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@read null
      val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      engine.snapshotView(family.definition, record)
      val steps = decodeWorkflowSteps(record.stepsJson)
      val artifacts = decodeArtifacts(record.artifactsJson)
      val finishCompleted = steps.any { step -> step.stepId == "pr" && step.status == "completed" }
      val currentStep = if (record.workflowStatus == "completed" || finishCompleted) "pr" else record.currentStepId
      val progressEvent = progressEventFrom(artifacts)
      // SKILL-64 Subtask 3 (F-R01): the declared-progress read MUST be
      // independent of the observability parse. Decode the declared event first,
      // then decode observability softly: a single corrupt
      // goal_observability_latest_event record must NOT return null for the
      // whole poll (callers wrap progress() in runCatching), which would
      // permanently disable deterministic declared liveness (AC20-AC23) and
      // revert to the legacy false-kill heuristics.
      val declaredProgressEvent = declaredProgressEventFrom(artifacts)
      val observabilityEvent = runCatching {
        goalObservabilityLatestEventFromArtifacts(artifacts, goalObservabilityEventValidator)
      }.getOrNull()
      GoalRunnerWorkflowProgress(
        workflowId = record.workflowId,
        workflowStatus = record.workflowStatus,
        currentStepId = currentStep,
        progressToken = record.progressToken(),
        latestDurableProgressEvent = progressEvent,
        latestGoalObservabilityEvent = observabilityEvent?.toProgressEvent(),
        latestDeclaredProgressEvent = declaredProgressEvent,
        latestLivenessSignal = observabilityEvent?.compactLivenessSummary()
          ?: progressEvent?.summary()
          ?: "workflow_status=${record.workflowStatus}; step=$currentStep",
        lastSnapshotUpdatedAt = record.updatedAt,
      )
    }

  override fun recordObservabilityEvent(
    request: GoalRunnerObservabilityRecordRequest,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    val family = workflowFamilyFor(unitOfWork.workflowStates, request.workflowId)
      ?: return@transaction false
    val record = family.get(unitOfWork.workflowStates, request.workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val observabilityPatch = GoalObservabilityArtifacts.patchForRuntimeEvent(
      input = GoalObservabilityArtifacts.RuntimeEventInput(
        artifacts = artifacts,
        request = request,
      ),
      validator = goalObservabilityEventValidator,
    )
    val updated = engine.updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = observabilityPatch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    family.save(unitOfWork.workflowStates, updated)
    true
  }

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String?): Boolean {
    // SKILL-64 Subtask 3 (AC21, AC25): validate the declared-progress event at
    // the durable write seam, loud-failing a malformed event exactly like the
    // sibling recordObservabilityEvent path. A bad event must never reach
    // artifacts_json where the soft supervisor read would silently drop it.
    val entryMap = request.event.toArtifactMap()
    goalProgressEventValidator.validate(entryMap, GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY)
    return appendHistoryArtifact(
      HistoryArtifactAppend(
        workflowId = request.workflowId,
        latestKey = GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY,
        historyKey = GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY,
        retentionLimit = GOAL_PROGRESS_HISTORY_LIMIT,
        entryMap = entryMap,
      ),
      dbPathOverride,
    )
  }

  override fun recordAttemptLedgerEntry(
    request: GoalRunnerAttemptLedgerRecordRequest,
    dbPathOverride: String?,
  ): Boolean = appendHistoryArtifact(
    HistoryArtifactAppend(
      workflowId = request.workflowId,
      latestKey = null,
      historyKey = GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY,
      retentionLimit = GOAL_ATTEMPT_LEDGER_LIMIT,
      entryMap = request.entry.toArtifactMap(),
    ),
    dbPathOverride,
  )

  // SKILL-64 Subtask 3: shared bounded append-and-cap write seam for the
  // declared-progress and attempt-ledger artifacts. Each appends one entry,
  // prunes to retentionLimit, and optionally mirrors the newest entry into a
  // latest-event key.
  override fun progressEvents(workflowId: String, dbPathOverride: String?): List<Map<String, Any?>> =
    database.transaction(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId)
        ?: return@transaction emptyList()
      val record = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction emptyList()
      (decodeArtifacts(record.artifactsJson)[GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY] as? List<*>)
        .orEmpty()
        .mapNotNull { item -> item as? Map<*, *> }
        .mapNotNull { item -> JsonSupport.anyToStringAnyMap(item) }
    }

  private fun appendHistoryArtifact(append: HistoryArtifactAppend, dbPathOverride: String?): Boolean =
    database.transaction(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, append.workflowId)
        ?: return@transaction false
      val record = family.get(unitOfWork.workflowStates, append.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existing = (artifacts[append.historyKey] as? List<*>)
        .orEmpty()
        .mapNotNull { item -> item as? Map<*, *> }
        .mapNotNull { item -> JsonSupport.anyToStringAnyMap(item) }
      // SKILL-64 Subtask 3 (F-A03): reuse the domain bounded-retention rule so
      // the durable write seam keeps the same sequence-ordered, oldest-pruned
      // semantics as GoalProgressHistory/GoalAttemptLedger.append(); the inline
      // append no longer diverges.
      val updatedHistory = appendBoundedHistoryBySequence(existing, append.entryMap, append.retentionLimit)
      val patch = buildMap<String, Any?> {
        put(append.historyKey, updatedHistory)
        append.latestKey?.let { put(it, append.entryMap) }
      }
      val updated = engine.updateRecord(
        family.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = patch,
          sessionId = record.sessionId.orEmpty(),
        ),
      )
      family.save(unitOfWork.workflowStates, updated)
      true
    }

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    // SKILL-67 Subtask 3 (AC2, AC4): resolve the owning family so a TASK_RUNTIME
    // child row is updated instead of no-op-returning false (which would trip
    // workerRequestAuditFailureStop). Returns false only when no family owns the
    // id, matching appendHistoryArtifact semantics.
    val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val record = family.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existing = (artifacts[WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY] as? List<*>)
      .orEmpty()
      .mapNotNull { item -> item as? Map<*, *> }
      .map { item -> JsonSupport.anyToStringAnyMap(item) }
    val updatedOutcomes = (existing + outcomes.map(GoalRunnerWorkerSubtaskRequestOutcome::toArtifactMap))
      .takeLast(WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT)
    val updated = engine.updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = mapOf(WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY to updatedOutcomes),
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    family.save(unitOfWork.workflowStates, updated)
    true
  }

  override fun ledgerSequenceWatermarks(
    issueKey: String,
    dbPathOverride: String?,
  ): GoalRunnerLedgerSequenceWatermarks = database.read(dbPathOverride) { unitOfWork ->
    val normalizedIssueKey = issueKey.trim()
    var maxLedger: Int? = null
    var maxProgress: Int? = null
    val backwardEdgeCounts = mutableMapOf<String, Int>()
    listOf(WorkflowFamily.TASK_RUNTIME).forEach { family ->
      family.list(unitOfWork.workflowStates, Int.MAX_VALUE).forEach { snapshot ->
        val artifacts = decodeArtifacts(snapshot.artifactsJson)
        if (goalContinuation(artifacts)?.issueKey != normalizedIssueKey) {
          return@forEach
        }
        maxLedger = maxHistorySequence(artifacts, GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY, maxLedger)
        maxProgress = maxHistorySequence(artifacts, GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY, maxProgress)
        backwardEdgeCountsFromLedger(artifacts).forEach { (key, count) ->
          backwardEdgeCounts.merge(key, count, ::maxOf)
        }
      }
    }
    GoalRunnerLedgerSequenceWatermarks(
      maxLedgerSequence = maxLedger,
      maxProgressSequence = maxProgress,
      backwardEdgeCounts = backwardEdgeCounts,
    )
  }

  override fun childWorkflowLoopIterations(workflowId: String, dbPathOverride: String?): Map<String, Int> =
    database.read(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@read emptyMap()
      val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@read emptyMap()
      val artifacts = decodeArtifacts(record.artifactsJson)
      val result = mutableMapOf<String, Int>()
      phaseRecordsFrom(artifacts).values.forEach { phaseRecord ->
        val loopId = phaseRecord.loopId ?: return@forEach
        val edgeIteration = phaseRecord.edgeIteration ?: return@forEach
        result.merge(loopId, edgeIteration, ::maxOf)
      }
      result
    }

  override fun readAttemptLedgerSummary(issueKey: String, dbPathOverride: String?): GoalRunnerAttemptLedgerSummary =
    database.read(dbPathOverride) { unitOfWork ->
      val normalizedIssueKey = issueKey.trim()
      val acc = AttemptLedgerAccumulator()
      listOf(WorkflowFamily.TASK_RUNTIME).forEach { family ->
        family.list(unitOfWork.workflowStates, Int.MAX_VALUE).forEach { snapshot ->
          val artifacts = decodeArtifacts(snapshot.artifactsJson)
          if (goalContinuation(artifacts)?.issueKey != normalizedIssueKey) return@forEach
          (artifacts[GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY] as? List<*>).orEmpty().forEach { item ->
            (item as? Map<*, *>)?.let(acc::accumulate)
          }
        }
      }
      acc.toSummary()
    }

  private fun loadContinuationCandidates(
    workflowStates: WorkflowStateRepository,
    issueKey: String,
    // SKILL-68: when present, a complete-without-SHA candidate may recover its SHA from measured
    // HEAD (the manifest-workflowId-independent heal). null keeps the read-only, no-measure path
    // for pure status callers.
    repoRoot: Path? = null,
  ): List<GoalContinuationCandidate> = listOf(WorkflowFamily.TASK_RUNTIME).flatMap { family ->
    family.list(workflowStates, Int.MAX_VALUE).mapNotNull { snapshot ->
      engine.snapshotView(family.definition, snapshot)
      val artifacts = decodeArtifacts(snapshot.artifactsJson)
      val goalContinuation = goalContinuation(artifacts) ?: return@mapNotNull null
      if (goalContinuation.issueKey != issueKey) {
        return@mapNotNull null
      }
      GoalContinuationCandidate(
        family = family,
        snapshot = snapshot,
        goalContinuation = goalContinuation,
        outcome = terminalOutcomeFor(snapshot, artifacts, goalContinuation) {
          repoRoot?.let { root -> gitOperations.headCommitSha(root).measuredCommitSha() }
        },
      )
    }
  }

  // SKILL-87: positive-evidence staleness check for a still-running candidate. Returns true only when
  // the evidence says the subtask is gone: a terminal own outcome, OR liveness signals that all aged
  // out of the window. The row's own updatedAt is the always-present backstop — every DB write stamps
  // updated_at, so a DB-backed candidate's liveness is never empty and an aged-out updatedAt yields
  // stale (closing the strand-forever path even with no declared/observed event). The declared
  // progress-event timestamp (firing from tick 1) remains the primary recent-liveness signal. Liveness
  // is genuinely empty only when updatedAt is absent/unparseable AND no declared/observed event exists;
  // that no-evidence-at-all case biases to alive as the defensive last resort. Best-effort: a decode
  // fault never throws and never false-kills.
  private fun candidateIsStale(candidate: GoalContinuationCandidate): Boolean = runCatching {
    candidate.outcome?.status?.let { return@runCatching it != GoalRunnerTerminalStatus.COMPLETE }
    val now = Instant.now()
    val window = STALENESS_EVIDENCE_WINDOW
    val liveness = candidateLivenessInstants(candidate)
    val recent = liveness.any { signal -> Duration.between(signal, now).let { !it.isNegative && it <= window } }
    liveness.isNotEmpty() && !recent
  }.getOrDefault(false)

  private fun candidateLivenessInstants(candidate: GoalContinuationCandidate): List<Instant> {
    val artifacts = decodeArtifacts(candidate.snapshot.artifactsJson)
    val declared = declaredProgressEventFrom(artifacts)?.timestamp
    val observed = runCatching {
      goalObservabilityLatestEventFromArtifacts(artifacts, goalObservabilityEventValidator)?.timestamp
    }.getOrNull()
    return listOfNotNull(declared, observed, candidate.snapshot.updatedAt).mapNotNull(::parseInstantOrNull)
  }

  private fun markBlocked(write: GoalRunnerBlockWrite): String {
    val steps = decodeWorkflowSteps(write.record.stepsJson)
    // Family-scoped: only the runtime family reconciles the resume boundary off the truthful
    // steps[] order (SKILL-85 subtask 1). The VERIFY family reconciliation keeps its historical
    // current-step fallback unchanged; PROSE rows never reach here (workflowFamilyFor loud-fails).
    // Loop-only steps (e.g. `implement_fix`) are excluded so the boundary scan mirrors the forward
    // transition, which skips them: otherwise a reconciled row with a clean review parks at
    // `implement_fix` (the first definition-ordered loop-only step still pending) instead of the
    // real forward boundary (`audit`). The runtime re-derives the actual phase from durable verdicts
    // on resume regardless, so this only corrects the advisory boundary, never execution.
    val definitionStepIds =
      if (write.family == WorkflowFamily.TASK_RUNTIME) {
        write.family.definition.stepIds.filterNot { it in write.family.loopOnlyStepIds }
      } else {
        emptyList()
      }
    val stepId = blockedStepId(write.record, steps, write.lastResumableStep, definitionStepIds)
    val attemptCount = steps.firstOrNull { it.stepId == stepId }?.attemptCount ?: 1
    val updated = engine.updateRecord(
      write.family.definition,
      write.record,
      WorkflowUpdateInput(
        workflowStatus = "blocked",
        currentStepId = stepId,
        stepUpdates = listOf(
          mapOf("step_id" to stepId, "status" to "blocked", "attempt_count" to attemptCount),
        ),
        artifactsPatch = buildMap {
          put("blocked_reason", write.blockedReason)
          write.supervisionEvent?.let { event -> put("supervision_event", event.toArtifactsMap()) }
        },
        sessionId = write.record.sessionId.orEmpty(),
      ),
    )
    write.family.save(write.workflowStates, updated)
    return stepId
  }

  // SKILL-175: the prose engine is retired. A `feature_task_workflows` row that decodes as
  // PROSE (or predates the `mode` column, which only ever held prose rows) is quarantined here —
  // every live goal-runner write/continue/lookup path funnels through this lookup, so raising here
  // refuses the row loudly for all of them rather than degrading to a deleted family or silently
  // treating the row as absent.
  private fun workflowFamilyFor(workflowStates: WorkflowStateRepository, workflowId: String): WorkflowFamily? {
    val featureTaskRow = workflowStates.getFeatureTaskWorkflow(workflowId)
    if (featureTaskRow != null) {
      return when (featureTaskRow.mode) {
        FeatureTaskWorkflowMode.RUNTIME -> WorkflowFamily.TASK_RUNTIME
        FeatureTaskWorkflowMode.PROSE, null -> throw LegacyProseWorkflowError(workflowId, featureTaskRow.issueKey)
      }
    }
    return if (workflowStates.getFeatureVerifyWorkflow(workflowId) != null) {
      WorkflowFamily.VERIFY
    } else {
      null
    }
  }
}

internal data class GoalContinuation(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
  val goalBranch: String?,
)

private data class GoalSubtaskIdentity(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
)

private data class HistoryArtifactAppend(
  val workflowId: String,
  val latestKey: String?,
  val historyKey: String,
  val retentionLimit: Int,
  val entryMap: Map<String, Any?>,
)

private data class GoalProgressEventRequiredFields(
  val eventKind: GoalProgressEventKind,
  val workflowId: String,
  val workflowPhase: String,
  val timestamp: String,
  val sequenceNumber: Int,
) {
  companion object {
    fun of(
      eventKind: GoalProgressEventKind?,
      workflowId: String?,
      workflowPhase: String?,
      timestamp: String?,
      sequenceNumber: Int?,
    ): GoalProgressEventRequiredFields? = GoalProgressEventRequiredFields(
      eventKind = eventKind ?: return null,
      workflowId = workflowId ?: return null,
      workflowPhase = workflowPhase ?: return null,
      timestamp = timestamp ?: return null,
      sequenceNumber = sequenceNumber ?: return null,
    )
  }
}

private data class GoalContinuationCandidate(
  val family: WorkflowFamily,
  val snapshot: WorkflowStateSnapshot,
  val goalContinuation: GoalContinuation,
  val outcome: GoalRunnerStoredOutcome?,
)

private data class GoalRunnerBlockWrite(
  val family: WorkflowFamily,
  val record: WorkflowStateSnapshot,
  val blockedReason: String,
  val lastResumableStep: String,
  val workflowStates: WorkflowStateRepository,
  val supervisionEvent: GoalRunnerSupervisionEvent?,
)

internal fun goalContinuation(artifacts: Map<String, Any?>): GoalContinuation? =
  (artifacts["goal_continuation"] as? Map<*, *>)?.let { payload ->
    val issueKey = payload["issue_key"]?.toString()?.takeIf(String::isNotBlank)
    val subtaskId = payload["subtask_id"].asGoalRunnerIntOrNull()
    if (issueKey == null || subtaskId == null) {
      null
    } else {
      GoalContinuation(
        issueKey = issueKey,
        subtaskId = subtaskId,
        suppressPr = payload["suppress_pr"] == true,
        goalBranch = payload["goal_branch"]?.toString()?.takeIf(String::isNotBlank),
      )
    }
  }

private fun goalReviewArtifacts(artifacts: Map<String, Any?>): GoalSubtaskReviewArtifacts? =
  GoalSubtaskReviewArtifactDecoder.decode(artifacts)

private fun validatedGoalReviewPasses(
  review: GoalSubtaskReviewArtifacts,
  phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  unitOfWork: UnitOfWork,
): List<GoalSubtaskReviewPassResult> {
  review.state.passResults.forEach { pass ->
    val rawResult = review.rawResults.getValue(pass.passNumber.toString())
    val output = phaseOutputValidator
      .validatePhaseOutput(rawResult, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      .requireAcceptedOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      .normalizedOutput
      .envelope
    val recordedVerdicts = GoalSubtaskReviewSummaryReducer.recordedVerdicts(unitOfWork, output)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(output, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output, findings)
    if (
      pass.verdict != outcome.verdict ||
      pass.unresolvedFindingCount != outcome.unresolvedFindingCount ||
      pass.findings != findings
    ) {
      throw InvalidGoalSubtaskReviewStateSchemaError(
        sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        fieldPath = "pass_results.${pass.passNumber}",
        reason =
        "must exactly match the verdict, unresolved count, and compact findings derived from " +
          "its durable raw review result.",
      )
    }
  }
  return review.state.passResults
}

private object ReviewRawOutputFallbackValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    if (JsonSupport.parseObjectOrNull(phaseOutputText) == null) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "must be a JSON object when no runtime schema validator is injected.",
      )
    }
  }

  override fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    validatePhaseOutputText(phaseOutputText, sourceLabel)
    return requireNotNull(JsonSupport.parseObjectOrNull(phaseOutputText))
      .let(JsonSupport::jsonElementToValue)
      .let(JsonSupport::anyToStringAnyMap)
      ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "must decode to a string-keyed object when no runtime schema validator is injected.",
      )
  }
}

private fun taskRuntimeRecordOrNull(
  workflowStates: WorkflowStateRepository,
  workflowId: String,
): WorkflowStateSnapshot? = try {
  WorkflowFamily.TASK_RUNTIME.get(workflowStates, workflowId)
} catch (error: InvalidWorkflowStateSchemaError) {
  if (error.message.orEmpty().contains("mode='")) {
    null
  } else {
    throw error
  }
}

// Status/discovery may load legacy prose parents; read-only control fallbacks must not assert
// runtime mode (WorkflowFamily.TASK_RUNTIME.get) or goal status loud-fails on those rows.
private fun featureTaskRecordForLegacyControls(
  workflowStates: WorkflowStateRepository,
  workflowId: String,
): WorkflowStateSnapshot? = workflowStates.getFeatureTaskWorkflow(workflowId)?.toSnapshot()

private fun terminalOutcomeFor(
  snapshot: WorkflowStateSnapshot,
  artifacts: Map<String, Any?>,
  goalContinuation: GoalContinuation,
  // Lazily measures HEAD only on the recovery path, so the cost is paid solely there.
  measuredCommitSha: () -> String? = { null },
): GoalRunnerStoredOutcome? {
  val stored = goalContinuationOutcome(
    artifacts = artifacts,
    issueKey = goalContinuation.issueKey,
    subtaskId = goalContinuation.subtaskId,
    suppressPr = goalContinuation.suppressPr,
  )
    // SKILL-68: a stored complete-without-SHA outcome is NOT authoritative for the SHA decision; it
    // falls through to the measure branch so the recovery path can heal it. Stored non-complete
    // statuses and complete-WITH-SHA outcomes remain authoritative and short-circuit as before.
    ?.takeUnless { it.status == GoalRunnerTerminalStatus.COMPLETE && it.commitSha.isNullOrBlank() }
    ?.copy(workflowId = snapshot.workflowId)
  if (stored != null) {
    // COMPLETE-with-SHA stays authoritative without the new check (AC-006). Non-complete statuses
    // require durable corroboration (SKILL-176) before short-circuiting.
    if (stored.status == GoalRunnerTerminalStatus.COMPLETE ||
      nonCompleteStoredOutcomeIsCorroborated(
        stored,
        derivedTerminalOutcomeFor(snapshot, artifacts, goalContinuation, measuredCommitSha),
        snapshot,
      )
    ) {
      return stored
    }
  }
  return derivedTerminalOutcomeFor(snapshot, artifacts, goalContinuation, measuredCommitSha)
}

internal fun derivedTerminalOutcomeFor(
  snapshot: WorkflowStateSnapshot,
  artifacts: Map<String, Any?>,
  goalContinuation: GoalContinuation,
  measuredCommitSha: () -> String?,
): GoalRunnerStoredOutcome? {
  val steps = decodeWorkflowSteps(snapshot.stepsJson)
  val commitSha = commitShaFrom(artifacts)
    ?: if (commitPushCompletedUnderSuppressPr(steps, goalContinuation.suppressPr)) measuredCommitSha() else null
  return terminalStatus(snapshot, steps, goalContinuation.suppressPr, commitSha)?.let { status ->
    GoalRunnerStoredOutcome(
      status = status,
      workflowId = snapshot.workflowId,
      commitSha = commitSha,
      blockedReason = blockedReasonFrom(artifacts, steps, status),
      lastResumableStep = snapshot.currentStepId,
      suppressPr = goalContinuation.suppressPr,
    )
  }
}

// SKILL-176 corroboration sources per non-complete status:
// - blocked: derived blocked status + blocked_reason (operator reopen clears durable blocked state, so
//   a reopened child's stale artifact falls through). blockedReasonFrom must read the same durable
//   reason sources the runtime writes — top-level (markBlocked) and goal_continuation_outcome
//   (persistGoalContinuationOutcome) — or a still-blocked child fails corroboration and is displaced.
// - failed: derived failed status from workflow/step state
// - paused: durable workflow_status == "paused"
// - timeout: staleness impossible — no independent durable derivation refutes a stored timeout
internal fun nonCompleteStoredOutcomeIsCorroborated(
  stored: GoalRunnerStoredOutcome,
  derived: GoalRunnerStoredOutcome?,
  snapshot: WorkflowStateSnapshot,
): Boolean = when (stored.status) {
  GoalRunnerTerminalStatus.BLOCKED ->
    derived?.status == GoalRunnerTerminalStatus.BLOCKED &&
      derived.blockedReason == stored.blockedReason
  GoalRunnerTerminalStatus.FAILED -> derived?.status == GoalRunnerTerminalStatus.FAILED
  GoalRunnerTerminalStatus.PAUSED -> snapshot.workflowStatus == "paused"
  GoalRunnerTerminalStatus.TIMEOUT -> true
  GoalRunnerTerminalStatus.COMPLETE,
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerTerminalStatus.RECONCILABLE,
  -> false
}

private fun List<GoalContinuationCandidate>.authoritativeOutcomesBySubtask(): Map<Int, GoalRunnerStoredOutcome> =
  groupBy { candidate -> candidate.goalContinuation.subtaskId }
    .mapNotNull { (subtaskId, candidates) ->
      candidates.selectAuthoritativeOutcome()?.let { outcome -> subtaskId to outcome }
    }
    .toMap()

private fun List<GoalContinuationCandidate>.selectAuthoritativeOutcome(): GoalRunnerStoredOutcome? {
  val completeWinner = asSequence()
    .filter { candidate -> candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE }
    .maxWithOrNull(compareBy<GoalContinuationCandidate> { it.snapshot.updatedAt }.thenBy { it.snapshot.workflowId })
  if (completeWinner != null) {
    return completeWinner.outcome
  }
  // SKILL-176: candidate.outcome already reflects corroboration (uncorroborated stored blocked outcomes
  // fall through), and transactional displacement supersedes the artifact so it cannot re-win.
  val fallbackWinner = asSequence()
    .filter { candidate -> candidate.outcome != null }
    .maxWithOrNull(compareBy<GoalContinuationCandidate> { it.snapshot.updatedAt }.thenBy { it.snapshot.workflowId })
  return fallbackWinner?.outcome
}

private fun staleRunningReason(
  staleWorkflowId: String,
  issueKey: String,
  subtaskId: Int,
  authoritative: GoalRunnerStoredOutcome?,
): String = authoritative?.let { outcome ->
  if (outcome.workflowId == staleWorkflowId) {
    "Goal status reconciliation closed inactive running child '$staleWorkflowId' for issue '$issueKey' " +
      "subtask $subtaskId because a terminal outcome was already durable."
  } else {
    "Goal status reconciliation closed stale running child '$staleWorkflowId' for issue '$issueKey' " +
      "subtask $subtaskId in favor of authoritative ${outcome.status.name.lowercase()} workflow " +
      "'${outcome.workflowId}'."
  }
} ?: (
  "Goal status reconciliation closed stale running child '$staleWorkflowId' for issue '$issueKey' " +
    "subtask $subtaskId because it was no longer active."
  )

internal fun goalContinuationOutcome(
  artifacts: Map<String, Any?>,
  issueKey: String,
  subtaskId: Int,
  suppressPr: Boolean,
): GoalRunnerStoredOutcome? = (artifacts["goal_continuation_outcome"] as? Map<*, *>)
  ?.takeIf { outcome -> outcome["issue_key"]?.toString() == issueKey }
  ?.takeIf { outcome -> outcome["subtask_id"].asGoalRunnerIntOrNull() == subtaskId }
  ?.let { outcome ->
    goalContinuationTerminalStatus(outcome["status"]?.toString())?.let { status ->
      GoalRunnerStoredOutcome(
        status = status,
        workflowId = outcome["workflow_id"]?.toString().orEmpty(),
        commitSha = outcome["commit_sha"]?.toString()?.takeIf(String::isNotBlank),
        blockedReason = outcome["blocked_reason"]?.toString()?.takeIf(String::isNotBlank),
        lastResumableStep = outcome["last_resumable_step"]?.toString()?.takeIf(String::isNotBlank),
        suppressPr = suppressPr,
      )
    }
  }

private fun missingResultPrefixTerminalOutcomeArtifact(
  output: Map<String, Any?>,
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
): Map<String, Any?>? = (JsonSupport.anyToStringAnyMap(output["subtask_outcome"]) ?: output)
  .takeIf { candidate -> candidate.matchesGoalContinuation(issueKey, subtaskId) }
  ?.let { candidate ->
    candidate["status"]?.toString()?.let(::goalContinuationTerminalStatus)?.let { status ->
      candidate.toMissingResultPrefixOutcomeArtifact(issueKey, subtaskId, workflowId, status)
    }
  }

private fun Map<String, Any?>.matchesGoalContinuation(issueKey: String, subtaskId: Int): Boolean {
  val candidateIssueKey = this["issue_key"]?.toString()?.takeIf(String::isNotBlank) ?: issueKey
  val candidateSubtaskId = this["subtask_id"].asGoalRunnerIntOrNull() ?: subtaskId
  return candidateIssueKey == issueKey && candidateSubtaskId == subtaskId
}

private fun Map<String, Any?>.toMissingResultPrefixOutcomeArtifact(
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
  status: GoalRunnerTerminalStatus,
): Map<String, Any?> = linkedMapOf<String, Any?>(
  "issue_key" to issueKey,
  "subtask_id" to subtaskId,
  "status" to status.toGoalContinuationWireStatus(),
  "workflow_id" to (this["workflow_id"]?.toString()?.takeIf(String::isNotBlank) ?: workflowId),
  "last_resumable_step" to (
    this["last_resumable_step"]?.toString()?.takeIf(String::isNotBlank) ?: "preplan"
    ),
).apply {
  this@toMissingResultPrefixOutcomeArtifact["commit_sha"]?.toString()?.takeIf(String::isNotBlank)
    ?.let { put("commit_sha", it) }
  this@toMissingResultPrefixOutcomeArtifact["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
    ?.let { put("blocked_reason", it) }
}

private fun GoalRunnerTerminalStatus.toGoalContinuationWireStatus(): String = when (this) {
  GoalRunnerTerminalStatus.COMPLETE -> "complete"
  GoalRunnerTerminalStatus.FAILED -> "failed"
  GoalRunnerTerminalStatus.BLOCKED -> "blocked"
  GoalRunnerTerminalStatus.TIMEOUT -> "timeout"
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME -> "no_terminal_store_outcome"
  GoalRunnerTerminalStatus.RECONCILABLE -> "reconcilable"
  GoalRunnerTerminalStatus.PAUSED -> "paused"
}

private fun goalContinuationTerminalStatus(status: String?): GoalRunnerTerminalStatus? = when (status) {
  "complete", "completed" -> GoalRunnerTerminalStatus.COMPLETE
  "failed" -> GoalRunnerTerminalStatus.FAILED
  "blocked" -> GoalRunnerTerminalStatus.BLOCKED
  "timeout", "timed_out" -> GoalRunnerTerminalStatus.TIMEOUT
  "paused" -> GoalRunnerTerminalStatus.PAUSED
  else -> null
}

private fun terminalStatus(
  snapshot: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  suppressPr: Boolean,
  commitSha: String?,
): GoalRunnerTerminalStatus? = when {
  commitPushCompletedUnderSuppressPr(steps, suppressPr) ->
    if (commitSha.isNullOrBlank()) {
      GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
    } else {
      GoalRunnerTerminalStatus.COMPLETE
    }
  snapshot.workflowStatus == "failed" || steps.any { it.status == "failed" } -> GoalRunnerTerminalStatus.FAILED
  snapshot.workflowStatus == "blocked" || liveBlockedStep(snapshot, steps) != null -> GoalRunnerTerminalStatus.BLOCKED
  snapshot.workflowStatus in setOf("completed", "abandoned") -> GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
  else -> null
}

/**
 * A fix loop leaves the step it abandoned marked blocked and moves on, so a blocked step earlier
 * than the current one is history rather than the child's present state. Deriving BLOCKED from it
 * pins the child terminal for the rest of the goal: the parent re-reports the abandoned step's
 * block on every later run and never relaunches. Only a block at or after the current step speaks
 * for where the child actually stands.
 */
private fun liveBlockedStep(snapshot: WorkflowStateSnapshot, steps: List<WorkflowStepState>): WorkflowStepState? {
  val currentIndex = steps.indexOfFirst { it.stepId == snapshot.currentStepId }
  if (currentIndex < 0) return steps.firstOrNull { it.status == "blocked" }
  return steps.drop(currentIndex).firstOrNull { it.status == "blocked" }
}

private fun blockedReasonFrom(
  artifacts: Map<String, Any?>,
  steps: List<WorkflowStepState>,
  status: GoalRunnerTerminalStatus,
): String? = artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
  // Normal runtime blocks persist the reason only under goal_continuation_outcome (via
  // FeatureTaskRuntimeRunner.persistGoalContinuationOutcome); top-level blocked_reason is the
  // reconcile markBlocked path. Reading both keeps still-blocked children corroborating (AC-003).
  ?: (artifacts["goal_continuation_outcome"] as? Map<*, *>)
    ?.get("blocked_reason")?.toString()?.takeIf(String::isNotBlank)
  ?: steps.firstOrNull { it.status in setOf("failed", "blocked") }
    ?.let { step -> "Workflow step '${step.stepId}' is ${step.status}." }
  ?: "Workflow reached a terminal state without a goal-continuation commit SHA."
    .takeIf { status == GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME }

private fun commitShaFrom(artifacts: Map<String, Any?>): String? =
  (artifacts["commit_push_result"] as? Map<*, *>)?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)

private fun commitPushCompletedUnderSuppressPr(steps: List<WorkflowStepState>, suppressPr: Boolean): Boolean =
  suppressPr && steps.any { it.stepId == "commit_push" && it.status == "completed" }

private fun WorkflowGitOperationResult.measuredCommitSha(): String? = value.trim().takeIf { ok && it.isNotBlank() }

// SKILL-64 Subtask 3 (F-D01): soft-decode the highest sequence_number in a
// bounded history/ledger artifact list. Malformed entries are skipped rather
// than throwing; the watermark read must never fail the run.
private fun maxHistorySequence(artifacts: Map<String, Any?>, historyKey: String, current: Int?): Int? {
  val entries = (artifacts[historyKey] as? List<*>).orEmpty()
  var max = current
  entries.forEach { item ->
    val sequence = (item as? Map<*, *>)?.get("sequence_number").asGoalRunnerIntOrNull()
    val currentMax = max
    if (sequence != null && (currentMax == null || sequence > currentMax)) {
      max = sequence
    }
  }
  return max
}

private class AttemptLedgerAccumulator {
  var blockedAttemptCount = 0
  var supervisorKillCount = 0
  val phaseAttemptCounts = mutableMapOf<String, Int>()
  val cumulativeFixIterations = mutableMapOf<String, Int>()
  val reAttemptCauseCounts = mutableMapOf<String, Int>()
  var findingsInScope: Int? = null

  fun accumulate(entry: Map<*, *>) {
    val action = entry["action"]?.toString() ?: return
    if (entry["stop_reason"] != null) {
      if (isBlockStopReason(entry["stop_reason"]?.toString())) blockedAttemptCount++
      entry["re_attempt_cause"]?.toString()?.takeIf(String::isNotBlank)?.let { cause ->
        reAttemptCauseCounts.merge(cause, 1, Int::plus)
      }
      entry["findings_in_scope"].asGoalRunnerIntOrNull()?.let { findingsInScope = it }
    }
    if (entry["diagnostic_class"]?.toString() == "supervisor_killed_confirmed_alive") supervisorKillCount++
    if (action == "child_activation" || action == "resume") {
      val step = entry["current_step"]?.toString()?.takeIf(String::isNotBlank)
        ?: entry["previous_step"]?.toString()?.takeIf(String::isNotBlank)
        ?: "initial_start"
      phaseAttemptCounts.merge(step, 1, Int::plus)
    }
    if (action == "backward_edge_entry") accumulateBackwardEdge(entry)
  }

  private fun accumulateBackwardEdge(entry: Map<*, *>) {
    val subtaskId = entry["subtask_id"].asGoalRunnerIntOrNull() ?: return
    val loopId = entry["loop_id"]?.toString()?.takeIf(String::isNotBlank) ?: return
    val count = entry["cumulative_loop_count"].asGoalRunnerIntOrNull() ?: return
    cumulativeFixIterations.merge("$subtaskId:$loopId", count, ::maxOf)
  }

  private fun isBlockStopReason(stopReason: String?): Boolean =
    stopReason != null && stopReason.lowercase() in BLOCK_STOP_REASONS

  fun toSummary() = GoalRunnerAttemptLedgerSummary(
    blockedAttemptCount = blockedAttemptCount,
    supervisorKillCount = supervisorKillCount,
    phaseAttemptCounts = phaseAttemptCounts,
    cumulativeFixIterations = cumulativeFixIterations,
    reAttemptCauseCounts = reAttemptCauseCounts,
    findingsInScope = findingsInScope,
  )
}

private val BLOCK_STOP_REASONS: Set<String> = setOf(
  "failed",
  "blocked",
  "policy_blocked",
  "dependencies_blocked",
  "pull_request_failed",
)

// Scans the attempt ledger for backward-edge entries and returns the highest cumulative_loop_count
// for each "subtaskId:loopId" pair. Used to seed the recorder's cumulative counters on resume.
private fun backwardEdgeCountsFromLedger(artifacts: Map<String, Any?>): Map<String, Int> {
  val entries = (artifacts[GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY] as? List<*>).orEmpty()
  val counts = mutableMapOf<String, Int>()
  entries.forEach { item ->
    val entry = item as? Map<*, *> ?: return@forEach
    if (entry["action"]?.toString() != "backward_edge_entry") return@forEach
    val subtaskId = entry["subtask_id"].asGoalRunnerIntOrNull() ?: return@forEach
    val loopId = entry["loop_id"]?.toString()?.takeIf(String::isNotBlank) ?: return@forEach
    val count = entry["cumulative_loop_count"].asGoalRunnerIntOrNull() ?: return@forEach
    val key = "$subtaskId:$loopId"
    counts.merge(key, count, ::maxOf)
  }
  return counts
}

// SKILL-87: accept BOTH ISO-8601 (declared/observed progress-event timestamps) AND the SQLite
// CURRENT_TIMESTAMP shape "yyyy-MM-dd HH:mm:ss" (space separator, no 'T', no zone) that
// WorkflowStateStore stamps into updated_at. Instant.parse alone always returns null for the latter,
// silently dropping the snapshot-update liveness signal. Best-effort: null only when both fail.
private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
  ?: runCatching {
    LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC)
  }.getOrNull()

private val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun progressEventFrom(artifacts: Map<String, Any?>): GoalRunnerProgressEvent? =
  (artifacts["progress_event"] as? Map<*, *>)
    ?.toGoalRunnerProgressEventOrNull()

// SKILL-64 Subtask 3 (AC20-AC23): decode the latest declared progress event for
// the supervisor read seam. Soft-decode: malformed records yield null rather
// than failing the read.
private fun declaredProgressEventFrom(artifacts: Map<String, Any?>): GoalProgressEvent? =
  (artifacts[GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY] as? Map<*, *>)?.toGoalProgressEventOrNull()

private fun Map<*, *>.toGoalProgressEventOrNull(): GoalProgressEvent? {
  val eventKind = this["event_kind"]?.toString()?.takeIf(String::isNotBlank)
    ?.let { value -> runCatching { GoalProgressEventKind.fromWire(value) }.getOrNull() }
  val workflowId = this["workflow_id"]?.toString()?.takeIf(String::isNotBlank)
  val workflowPhase = this["workflow_phase"]?.toString()?.takeIf(String::isNotBlank)
  val timestamp = this["timestamp"]?.toString()?.takeIf(String::isNotBlank)
  val sequenceNumber = this["sequence_number"].asGoalRunnerIntOrNull()
  val required = GoalProgressEventRequiredFields.of(eventKind, workflowId, workflowPhase, timestamp, sequenceNumber)
    ?: return null
  return runCatching {
    GoalProgressEvent(
      eventKind = required.eventKind,
      workflowId = required.workflowId,
      workflowPhase = required.workflowPhase,
      // SKILL-64 Subtask 3 (F-C01): process_alive is the authoritative liveness
      // signal. On a missing/null/unparseable value, bias toward NOT alive so a
      // corrupt record cannot mask an unresponsive child (AC23). Only an
      // explicit boolean true keeps the child considered alive.
      processAlive = this["process_alive"] == true,
      sequenceNumber = required.sequenceNumber,
      timestamp = required.timestamp,
      stepId = this["step_id"]?.toString()?.takeIf(String::isNotBlank),
      operationName = this["operation_name"]?.toString()?.takeIf(String::isNotBlank),
      operationKind = this["operation_kind"]?.toString()?.takeIf(String::isNotBlank),
      expectedLong = this["expected_long"] == true,
      outcome = this["outcome"]?.toString()?.takeIf(String::isNotBlank)
        ?.let { value -> runCatching { GoalProgressOutcome.fromWire(value) }.getOrNull() }
        ?: GoalProgressOutcome.NONE,
    )
  }.getOrNull()
}

private fun Map<*, *>.toGoalRunnerProgressEventOrNull(): GoalRunnerProgressEvent? {
  val stepId = this["step_id"]?.toString()?.takeIf(String::isNotBlank)
  val kind = this["kind"]?.toString()?.takeIf(String::isNotBlank)
  val timestamp = this["timestamp"]?.toString()?.takeIf(String::isNotBlank)
  return if (stepId != null && kind != null && timestamp != null) {
    GoalRunnerProgressEvent(
      stepId = stepId,
      attemptCount = this["attempt_count"].asGoalRunnerIntOrNull() ?: 0,
      kind = kind,
      message = this["message"]?.toString().orEmpty(),
      sequence = this["sequence"].asGoalRunnerIntOrNull() ?: 0,
      timestamp = timestamp,
    )
  } else {
    null
  }
}

private fun GoalRunnerProgressEvent.summary(): String = buildString {
  append("durable_progress step=")
  append(stepId)
  append(" attempt=")
  append(attemptCount)
  append(" kind=")
  append(kind)
  append(" sequence=")
  append(sequence)
  append(" at=")
  append(timestamp)
  if (message.isNotBlank()) {
    append(" message=")
    append(message)
  }
}

private fun skillbill.workflow.model.GoalObservabilityEvent.toProgressEvent(): GoalObservabilityProgressEvent =
  GoalObservabilityProgressEvent(
    issueKey = issueKey,
    subtaskId = subtaskId,
    workflowPhase = workflowPhase,
    workerRole = workerRole,
    livenessClass = livenessClass,
    activitySummary = activitySummary,
    sequenceNumber = sequenceNumber,
    timestamp = timestamp,
  )

private fun GoalRunnerSupervisionEvent.toArtifactsMap(): Map<String, Any?> = linkedMapOf(
  "phase" to phase,
  "reason" to reason,
  "continuation_mode" to continuationMode,
  "process_state" to processState,
  "workflow_id" to workflowId,
  "step_id" to stepId,
  "last_durable_progress" to lastDurableProgress,
  "last_workflow_snapshot_at" to lastWorkflowSnapshotAt,
  "last_file_activity_at" to lastFileActivityAt,
  "last_output_at" to lastOutputAt,
)

private const val WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY = "goal_worker_subtask_request_outcomes"
private const val WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT = 50

private fun GoalRunnerWorkerSubtaskRequestOutcome.toArtifactMap(): Map<String, Any?> = when (this) {
  is GoalRunnerWorkerSubtaskRequestOutcome.Accepted -> linkedMapOf(
    "status" to "accepted",
    "source_stream" to sourceStream,
    "request" to request.toArtifactMap(),
    "subtask_id" to subtask.id,
    "spec_path" to subtask.specPath,
  )
  is GoalRunnerWorkerSubtaskRequestOutcome.Queued -> linkedMapOf(
    "status" to "queued",
    "source_stream" to sourceStream,
    "request" to request.toArtifactMap(),
    "reason" to reason,
  )
  is GoalRunnerWorkerSubtaskRequestOutcome.Rejected -> linkedMapOf(
    "status" to "rejected",
    "source_stream" to sourceStream,
    "reason" to reason.name.lowercase(),
    "message" to message,
  )
  is GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation -> linkedMapOf(
    "status" to "requires_operator_confirmation",
    "source_stream" to sourceStream,
    "request" to request.toArtifactMap(),
    "reason" to reason,
  )
}

private fun GoalRunnerWorkerSubtaskRequest.toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "name" to name,
  "spec_path" to specPath,
  "rationale" to rationale,
  "depends_on_subtask_ids" to dependsOnSubtaskIds,
  "requires_operator_confirmation" to requiresOperatorConfirmation,
).filterValues { value -> value != null }

private fun WorkflowStateSnapshot.progressToken(): String = listOf(
  workflowId,
  workflowStatus,
  currentStepId,
  stepsJson,
  artifactsJson,
  updatedAt.orEmpty(),
  finishedAt.orEmpty(),
).joinToString("\n")

internal fun decodeWorkflowSteps(stepsJson: String): List<WorkflowStepState> {
  val element = runCatching { JsonSupport.json.parseToJsonElement(stepsJson) }.getOrNull() ?: return emptyList()
  return (JsonSupport.jsonElementToValue(element) as? List<*>).orEmpty().mapNotNull { raw ->
    val item = raw as? Map<*, *> ?: return@mapNotNull null
    WorkflowStepState(
      stepId = item["step_id"]?.toString().orEmpty(),
      status = item["status"]?.toString().orEmpty(),
      attemptCount = item["attempt_count"].asGoalRunnerIntOrNull() ?: 0,
    )
  }
}

// Resolves the step a blocked/crashed row should resume from off the truthful steps[] (lockstep
// with the runtime's per-phase records since SKILL-85 subtask 1). A running step wins; otherwise
// the first step that is not completed/skipped in definition order is the real resume boundary
// (e.g. completed preplan/plan with a never-started implement resumes at implement, not preplan).
// Only when steps[] carries no resumable boundary do we fall back to the coarse current step.
private fun blockedStepId(
  record: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  requestedStepId: String,
  definitionStepIds: List<String>,
): String = requestedStepId.takeIf { stepId ->
  stepId.isNotBlank() && steps.firstOrNull { step -> step.stepId == stepId }?.status == "running"
}
  ?: steps.firstOrNull { step -> step.status == "running" }?.stepId
  ?: firstUnfinishedStepId(steps, definitionStepIds)
  ?: record.currentStepId.takeIf(String::isNotBlank)
  ?: requestedStepId.takeIf(String::isNotBlank)
  ?: "preplan"

// The first definition-ordered step whose truthful status is neither completed nor skipped, i.e.
// the earliest phase still owing work. Null when every step is terminal-done.
private fun firstUnfinishedStepId(steps: List<WorkflowStepState>, definitionStepIds: List<String>): String? {
  val statusByStepId = steps.associate { step -> step.stepId to step.status }
  return definitionStepIds.firstOrNull { stepId ->
    statusByStepId[stepId]?.let { status -> status != "completed" && status != "skipped" } ?: true
  }
}

private fun Any?.asGoalRunnerIntOrNull(): Int? = when (this) {
  is Int -> this
  is Number -> toInt()
  is String -> toIntOrNull()
  else -> null
}
