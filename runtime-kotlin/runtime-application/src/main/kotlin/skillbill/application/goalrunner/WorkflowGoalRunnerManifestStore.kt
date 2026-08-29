package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.planning.GoalChildPlanningHydrator
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toSnapshot
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import java.nio.file.Path
import java.time.Clock

@Inject
class WorkflowGoalRunnerManifestStore(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
  phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  private val clock: Clock = Clock.systemUTC(),
) : GoalRunnerManifestStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  private val planningHydrator = GoalChildPlanningHydrator(phaseOutputValidator, planningProjectionValidator)
  private val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
  private val manifestLoader = WorkflowGoalRunnerManifestLoader(
    database,
    decompositionManifestValidator,
    decompositionManifestFileStore,
    engine,
    parentProjection,
  )
  private val projectionPersistence = WorkflowGoalRunnerManifestProjectionPersistence(
    database,
    engine,
    parentProjection,
    decompositionManifestValidator,
  )
  private val childWorkflowPersistence = WorkflowGoalRunnerChildWorkflowPersistence(
    engine,
    planningHydrator,
    parentProjection,
    decompositionManifestValidator,
  )
  private val scopedReplanPersistence = WorkflowGoalRunnerScopedReplanPersistence(projectionPersistence)
  private val controls = GoalRunnerControlCoordinator(
    database,
    decompositionManifestValidator,
    clock,
  ) { unitOfWork, state ->
    projectionPersistence.saveInTransaction(unitOfWork, state)
  }

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

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> manifestLoader.findProjectedManifest(root, issueKey) }
    val stored = manifestLoader.loadFromWorkflowStore(issueKey, dbPathOverride, projected)
    if (manifestLoader.shouldRefreshFromCompleteProjection(stored, projected)) {
      return save(
        requireNotNull(stored).copy(manifest = requireNotNull(projected), repoRoot = repoRoot),
        dbPathOverride,
      )
    }
    return stored?.copy(repoRoot = repoRoot) ?: projected?.let { manifest ->
      manifestLoader.importFromManifestProjection(manifest, dbPathOverride)?.copy(repoRoot = repoRoot)
    }
  }

  override fun readByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root -> manifestLoader.findProjectedManifest(root, issueKey) }
    val stored = manifestLoader.loadFromWorkflowStore(issueKey, dbPathOverride, projected)
    return manifestLoader.readProjection(stored, projected, repoRoot)
  }

  override fun readByIssueKeyIfPresent(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? {
    val projected = repoRoot?.let { root ->
      manifestLoader.findProjectedManifest(root, issueKey, recoverPending = false)
    }
    val stored = manifestLoader.loadFromWorkflowStoreIfPresent(issueKey, dbPathOverride, projected)
    return manifestLoader.readProjection(stored, projected, repoRoot)
  }

  override fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String?): GoalRunnerManifestState? =
    manifestLoader.loadFromWorkflowStore(issueKey, dbPathOverride, currentProjectedManifest = null)

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    val saved = projectionPersistence.save(state, dbPathOverride)
    writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }

  override fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    projectionPersistence.save(state, dbPathOverride).state

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
      val projection = projectionPersistence.saveInTransaction(
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
    writeProjectionFile(state, saved.projectionArtifactsJson)
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
      projectionPersistence.saveInTransaction(unitOfWork, state.copy(manifest = recoveredManifest))
    }
    writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }

  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult {
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      scopedReplanPersistence.executeScopedReplan(unitOfWork, state, subtaskId, options)
    }
    writeProjectionFile(state, saved.second)
    return saved.first
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
      childWorkflowPersistence.saveInTransaction(unitOfWork, state, setup)
    }
    writeProjectionFile(state, saved.projectionArtifactsJson)
    return saved.state
  }

  override fun reviewMode(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): CodeReviewExecutionMode? = database.read(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)?.codeReviewMode
      ?: featureTaskRecordForLegacyControls(unitOfWork.workflowStates, parentWorkflowId)
        ?.let { record -> reviewPolicyFromLegacyArtifacts(decodeArtifacts(record.artifactsJson))?.codeReviewMode }
  }

  override fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
    dbPathOverride: String?,
  ): CodeReviewExecutionMode = database.transaction(dbPathOverride) { unitOfWork ->
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

  private fun writeProjectionFile(state: GoalRunnerManifestState, projectionArtifactsJson: String) {
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: Path.of("").toAbsolutePath(),
      projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
  }
}
