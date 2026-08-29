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
import skillbill.application.goalrunner.planning.GoalChildPlanningHydrator
import skillbill.application.goalrunner.planning.cascadeEligiblePlanSubtaskIds
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
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
import kotlin.coroutines.cancellation.CancellationException
import skillbill.contracts.JsonSupport
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalProgressEventSchemaError
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
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.goalrunner.model.GoalChildWorkflowDeletionScope
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.goal.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts
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
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifacts
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalObservabilityEvent
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry


internal fun reconcileControlStateForManifest(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
  validator: DecompositionManifestValidator,
) {
  val parent = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId) ?: return
  val manifest = parent.decompositionRuntime(validator) ?: return
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  val reconciled = existing.reconciledForCurrentSubtask(manifest.currentSubtaskIntent.subtaskId)
  if (reconciled != existing) {
    unitOfWork.goalRunnerControls.persistControlState(parentWorkflowId, reconciled)
  }
}
internal class GoalRunnerControlCoordinator(
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
    reconcileControlStateForManifest(unitOfWork, parentWorkflowId, decompositionManifestValidator)
    unitOfWork.goalRunnerControls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken)
  }

  fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    reconcileControlStateForManifest(unitOfWork, parentWorkflowId, decompositionManifestValidator)
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
          throw GoalRunnerLaunchAuthorizationDeniedException(controls)
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
          throw GoalRunnerLaunchAuthorizationDeniedException(controls)
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
