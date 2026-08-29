package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowListResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.normalizeIssueKey
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.nio.file.Path

@Inject
class WorkflowService(
  private val database: DatabaseSessionFactory,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator) {
    val resolved = gitOperations.repositoryFingerprint(Path.of("").toAbsolutePath())
    check(resolved.ok) { resolved.error }
    resolved.value.orEmpty()
  }
  private val featureTaskAbandon = WorkflowServiceFeatureTaskAbandon(engine)
  private val blockedPhaseRetry = WorkflowServiceBlockedPhaseRetry(
    engine,
    decompositionManifestValidator,
    decompositionManifestFileStore,
  )
  private val featureTaskIdentityRepair = WorkflowServiceFeatureTaskIdentityRepair(engine)

  fun open(
    kind: WorkflowFamilyKind,
    sessionId: String = "",
    currentStepId: String? = null,
    dbOverride: String? = null,
    issueKey: String? = null,
    repositoryIdentity: String? = null,
    governedSpecPath: String? = null,
    routeScope: FeatureTaskRouteScope = FeatureTaskRouteScope.STANDALONE,
  ): WorkflowOpenResult {
    val hasIdentityCoordinates = repositoryIdentity != null || governedSpecPath != null
    val hasIncompleteIdentity = hasIncompleteFeatureTaskIdentity(
      kind,
      hasIdentityCoordinates,
      issueKey,
      repositoryIdentity,
      governedSpecPath,
    )
    if (hasIncompleteIdentity) {
      return WorkflowOpenResult.Error(
        workflowId = "unassigned",
        error = INCOMPLETE_FEATURE_TASK_IDENTITY_ERROR,
      )
    }
    val family = kind.workflowFamily()
    val stepId = currentStepId ?: family.definition.defaultInitialStepId
    val workflowId = generateWorkflowId(family.definition.workflowIdPrefix)
    val effectiveSessionId = resolveEffectiveSessionId(kind, sessionId, family.definition, workflowId)
    WorkflowEngine.validateOpen(family.definition, stepId)?.let { error ->
      return WorkflowOpenResult.Error(workflowId, error)
    }
    val executionIdentity = buildFeatureTaskExecutionIdentity(
      kind,
      hasIdentityCoordinates,
      workflowId,
      issueKey,
      repositoryIdentity,
      governedSpecPath,
      routeScope,
    )
    return database.transaction(dbOverride) { unitOfWork ->
      val record = engine.openRecord(family.definition, workflowId, effectiveSessionId, stepId)
      family.saveRecord(
        unitOfWork.workflowStates,
        record.toRecord().copy(
          startedAt = null,
          issueKey = normalizeIssueKey(issueKey),
        ),
      )
      executionIdentity?.let(unitOfWork.workflowStates::saveFeatureTaskExecutionIdentity)
      val saved = family.get(unitOfWork.workflowStates, workflowId) ?: record
      val currentStep = engine.snapshotView(family.definition, saved).steps
        .firstOrNull { it.stepId == stepId }
      val launchProjection = launchProjectionIfReady(
        engine,
        family.definition,
        engine.snapshotView(family.definition, saved),
        stepId,
        currentStep?.attemptCount ?: 0,
      )
      WorkflowOpenResult.Ok(
        workflowId = saved.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        snapshot = engine.snapshotView(family.definition, saved),
        launchProjection = launchProjection,
      )
    }
  }

  fun openFeatureTask(
    kind: WorkflowFamilyKind,
    sessionId: String = "",
    currentStepId: String? = null,
    dbOverride: String? = null,
    issueKey: String,
    repositoryIdentity: String,
    governedSpecPath: String,
    routeScope: FeatureTaskRouteScope = FeatureTaskRouteScope.STANDALONE,
  ): WorkflowOpenResult {
    require(kind in FEATURE_TASK_FAMILY_KINDS) {
      "Only runtime feature-task workflows use execution identity."
    }
    return open(
      kind,
      sessionId,
      currentStepId,
      dbOverride,
      issueKey,
      repositoryIdentity,
      governedSpecPath,
      routeScope,
    )
  }

  fun update(
    kind: WorkflowFamilyKind,
    request: WorkflowUpdateRequest,
    dbOverride: String? = null,
  ): WorkflowUpdateResult {
    val family = kind.workflowFamily()
    val input = request.toWorkflowUpdateInput()
    WorkflowEngine.validateUpdate(family.definition, input)?.let { error ->
      return WorkflowUpdateResult.Error(request.workflowId, error)
    }
    var projectionArtifactsJson: String? = null
    val result = database.transaction(dbOverride) { unitOfWork ->
      val existing = family.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction WorkflowUpdateResult.Error(
          request.workflowId,
          "Unknown workflow_id '${request.workflowId}'.",
        )
      val runtimeInput = family.withDecompositionRuntime(
        existing,
        input,
        request.workflowId,
        decompositionManifestValidator,
        decompositionManifestFileStore,
      )
      val effectiveInput = runtimeInput.input.withGoalObservabilityArtifacts(
        existing = existing,
        workflowId = request.workflowId,
        validator = goalObservabilityEventValidator,
        gitOperations = gitOperations,
      )
      val updatedRecord = engine.updateRecord(family.definition, existing, effectiveInput)
      family.save(unitOfWork.workflowStates, updatedRecord)
      val updated = family.get(unitOfWork.workflowStates, request.workflowId) ?: updatedRecord
      if (runtimeInput.updated) {
        projectionArtifactsJson = updated.artifactsJson
        engine.syncDecompositionParentRuntime(
          family,
          updated,
          request.workflowId,
          unitOfWork,
          decompositionManifestValidator,
        )
      }
      buildUpdateOk(engine, family.definition, updated, effectiveInput, unitOfWork.dbPath.toString())
    }
    projectionArtifactsJson?.let { artifactsJson ->
      DecompositionManifestWriter.writeProjectionFromWorkflowState(
        Path.of("").toAbsolutePath(),
        artifactsJson,
        decompositionManifestValidator,
        decompositionManifestFileStore,
      )
    }
    return result
  }

  fun abandonFeatureTaskRuntime(workflowId: String, reason: String, dbOverride: String? = null): WorkflowUpdateResult {
    val normalizedReason = reason.trim()
    if (normalizedReason.isEmpty() || normalizedReason.length > MAX_ABANDONMENT_REASON_LENGTH) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Abandonment reason must contain 1..$MAX_ABANDONMENT_REASON_LENGTH characters.",
      )
    }
    return database.transaction(dbOverride) { unitOfWork ->
      val existingRecord = unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)
        ?: return@transaction WorkflowUpdateResult.Error(
          workflowId,
          "Unknown feature-task workflow_id '$workflowId'.",
          unitOfWork.dbPath.toString(),
        )
      when (existingRecord.mode) {
        FeatureTaskWorkflowMode.RUNTIME -> featureTaskAbandon.abandonRuntimeFeatureTask(
          unitOfWork,
          existingRecord.toSnapshot(),
          normalizedReason,
        )
        FeatureTaskWorkflowMode.PROSE, null -> featureTaskAbandon.abandonLegacyProseFeatureTask(
          unitOfWork,
          existingRecord,
          normalizedReason,
        )
      }
    }
  }

  fun retryBlockedFeatureTaskRuntimePhase(
    workflowId: String,
    phaseId: String,
    reason: String,
    dbOverride: String? = null,
  ): WorkflowUpdateResult = blockedPhaseRetry.retry(database, workflowId, phaseId, reason, dbOverride)

  fun repairFeatureTaskRuntimeIdentity(
    workflowId: String,
    issueKey: String,
    repositoryIdentity: String,
    governedSpecPath: String,
    reason: String,
    dbOverride: String? = null,
  ): WorkflowUpdateResult {
    val normalizedReason = reason.trim()
    if (normalizedReason.isEmpty() || normalizedReason.length > MAX_ABANDONMENT_REASON_LENGTH) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Identity-repair reason must contain 1..$MAX_ABANDONMENT_REASON_LENGTH characters.",
      )
    }
    val normalizedIssueKey = requireNotNull(normalizeIssueKey(issueKey)).uppercase()
    return database.transaction(dbOverride) { unitOfWork ->
      featureTaskIdentityRepair.repair(
        unitOfWork,
        workflowId,
        normalizedIssueKey,
        repositoryIdentity,
        governedSpecPath,
        normalizedReason,
      )
    }
  }

  fun get(kind: WorkflowFamilyKind, workflowId: String, dbOverride: String? = null): WorkflowGetResult =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val record = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@read WorkflowGetResult.Error(
          workflowId,
          "Unknown workflow_id '$workflowId'.",
          unitOfWork.dbPath.toString(),
        )
      WorkflowGetResult.Ok(
        workflowId = record.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        snapshot = engine.snapshotView(family.definition, record),
      )
    }

  fun list(kind: WorkflowFamilyKind, limit: Int = DEFAULT_LIST_LIMIT, dbOverride: String? = null): WorkflowListResult =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val rows = family.list(unitOfWork.workflowStates, limit)
      WorkflowListResult(
        dbPath = unitOfWork.dbPath.toString(),
        workflowCount = rows.size,
        workflows = rows.map { engine.summaryView(family.definition, it) },
      )
    }

  fun latest(kind: WorkflowFamilyKind, dbOverride: String? = null): WorkflowLatestResult =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val record = family.latest(unitOfWork.workflowStates)
        ?: return@read WorkflowLatestResult.Error(
          dbPath = unitOfWork.dbPath.toString(),
          error = "No ${family.humanName} workflows found.",
        )
      WorkflowLatestResult.Ok(
        dbPath = unitOfWork.dbPath.toString(),
        summary = engine.summaryView(family.definition, record),
      )
    }

  fun resume(kind: WorkflowFamilyKind, workflowId: String, dbOverride: String? = null): WorkflowResumeResult =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val record = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@read WorkflowResumeResult.Error(
          workflowId,
          "Unknown workflow_id '$workflowId'.",
          unitOfWork.dbPath.toString(),
        )
      WorkflowResumeResult.Ok(
        workflowId = record.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        resume = engine.resumeView(family.definition, record),
      )
    }

  fun continueWorkflow(
    kind: WorkflowFamilyKind,
    workflowId: String,
    subtaskId: Int? = null,
    dbOverride: String? = null,
  ): WorkflowContinueResult {
    var projectionArtifactsJson: String? = null
    val result = database.transaction(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      var record = family.get(unitOfWork.workflowStates, workflowId)
      if (record == null && family == WorkflowFamily.TASK_RUNTIME) {
        val resolved =
          DecompositionWorkflowContinuation(
            engine,
            gitOperations,
            decompositionManifestValidator,
            decompositionManifestFileStore,
          ).continueDecomposedParentByIssueKey(workflowId, unitOfWork, subtaskId)
        projectionArtifactsJson = resolved.projectionArtifactsJson ?: projectionArtifactsJson
        return@transaction resolved.result
      }
      record ?: return@transaction WorkflowContinueResult.UnknownWorkflow(
        dbPath = unitOfWork.dbPath.toString(),
        workflowId = workflowId,
      )
      engine.continueExistingWorkflow(
        family,
        record,
        unitOfWork,
        decompositionManifestValidator,
      ).also { continuation ->
        projectionArtifactsJson = continuation.projectionArtifactsJson ?: projectionArtifactsJson
      }.result
    }
    projectionArtifactsJson?.let { artifactsJson ->
      DecompositionManifestWriter.writeProjectionFromWorkflowState(
        Path.of("").toAbsolutePath(),
        artifactsJson,
        decompositionManifestValidator,
        decompositionManifestFileStore,
      )
    }
    return result
  }
}
