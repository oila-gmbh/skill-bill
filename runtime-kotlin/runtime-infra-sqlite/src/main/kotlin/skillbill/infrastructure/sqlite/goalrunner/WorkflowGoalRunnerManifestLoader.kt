package skillbill.infrastructure.sqlite.goalrunner
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.persistence.GoalParentProjectionWriter
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.issuekey.normalizeRequiredIssueKey
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.decomposition.runtime.resolveDecompositionManifest
import skillbill.ports.workflow.decomposition.runtime.withParentStatus
import skillbill.ports.workflow.persistence.decompositionRuntime
import skillbill.ports.workflow.persistence.findDecomposedParentOrCorruptFallback
import skillbill.ports.workflow.persistence.findDecomposedParentWorkflow
import skillbill.ports.workflow.persistence.generateWorkflowId
import skillbill.ports.workflow.persistence.migrateLegacyGoalRunnerControls
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.ports.workflow.persistence.requireRuntimeModeForEngineWrite
import skillbill.ports.workflow.persistence.toRecord
import skillbill.ports.workflow.persistence.toSnapshot
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.nio.file.Path

internal class WorkflowGoalRunnerManifestLoader(
  private val database: DatabaseSessionFactory,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
  private val engine: WorkflowEngine,
  private val parentProjection: GoalParentProjectionWriter,
) {
  fun findProjectedManifest(repoRoot: Path, issueKey: String, recoverPending: Boolean = true) =
    resolveDecompositionManifest(
      repoRoot = repoRoot,
      issueKey = issueKey,
      fileStore = decompositionManifestFileStore,
      validator = decompositionManifestValidator,
      recoverPending = recoverPending,
    )

  fun loadFromWorkflowStore(
    issueKey: String,
    dbPathOverride: String?,
    currentProjectedManifest: DecompositionManifest? = null,
  ): GoalRunnerManifestState? = database.read(dbPathOverride) { unitOfWork ->
    loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest)
  }

  fun loadFromWorkflowStoreIfPresent(
    issueKey: String,
    dbPathOverride: String?,
    currentProjectedManifest: DecompositionManifest? = null,
  ): GoalRunnerManifestState? = database.readIfPresent(dbPathOverride) { unitOfWork ->
    loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest)
  }

  fun loadFromWorkflowUnitOfWork(
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

  fun importFromManifestProjection(
    manifest: DecompositionManifest,
    dbPathOverride: String?,
  ): GoalRunnerManifestState? = database.transaction(dbPathOverride) { unitOfWork ->
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

  fun readProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = when {
    shouldRefreshFromCompleteProjection(stored, projected) -> requireNotNull(stored).copy(
      manifest = requireNotNull(projected),
      repoRoot = repoRoot,
    )
    stored != null -> stored.copy(repoRoot = repoRoot)
    projected != null -> GoalRunnerManifestState(
      parentWorkflowId = "",
      dbPath = "",
      manifest = projected,
      repoRoot = repoRoot,
    )
    else -> null
  }

  fun shouldRefreshFromCompleteProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
  ): Boolean = stored != null &&
    projected != null &&
    projected.isCompleteGoalProjection() &&
    !stored.manifest.isCompleteGoalProjection()
}

internal fun mergeConcurrentGoalProgress(
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

private fun DecompositionManifest.isCompleteGoalProjection(): Boolean =
  status == "complete" && currentSubtaskIntent.action == "complete" && subtasks.all { subtask ->
    subtask.status in setOf("complete", "skipped") &&
      (subtask.status == "skipped" || !subtask.commitSha.isNullOrBlank())
  }
