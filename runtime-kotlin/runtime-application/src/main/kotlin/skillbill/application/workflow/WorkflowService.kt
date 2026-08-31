package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestProjectionSupport
import skillbill.application.normalizeIssueKey
import skillbill.application.workflow.model.BuildFeatureTaskExecutionIdentityArgs
import skillbill.application.workflow.model.ContinueExistingWorkflowArgs
import skillbill.application.workflow.model.DecompositionRuntimeWriteArgs
import skillbill.application.workflow.model.FeatureTaskIdentityRepairArgs
import skillbill.application.workflow.model.RepairFeatureTaskRuntimeIdentityArgs
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowListResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.model.WorkflowServiceDeps
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.engine.WorkflowEngine

@Inject
class WorkflowService(deps: WorkflowServiceDeps) {
  private val database = deps.database
  private val gitOperations = deps.gitOperations
  private val decompositionManifestFileStore = deps.decompositionManifestFileStore
  private val workflowSnapshotValidator = deps.workflowSnapshotValidator
  private val decompositionManifestValidator = deps.decompositionManifestValidator
  private val decompositionManifestWriter = deps.decompositionManifestWriter
  private val repositoryRoot = deps.repositoryRoot
  val goalObservabilityEventValidator = deps.goalObservabilityEventValidator

  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator) {
    val resolved = gitOperations.repositoryFingerprint(repositoryRoot.path)
    check(resolved.ok) { resolved.error }
    resolved.value.orEmpty()
  }
  private val featureTaskAbandon = WorkflowServiceFeatureTaskAbandon(engine)
  private val blockedPhaseRetry = WorkflowServiceBlockedPhaseRetry(
    engine,
    decompositionManifestValidator,
    decompositionManifestFileStore,
    decompositionManifestWriter,
    repositoryRoot,
  )
  private val featureTaskIdentityRepair = WorkflowServiceFeatureTaskIdentityRepair(engine)

  fun open(args: WorkflowServiceOpenArgs): WorkflowOpenResult {
    incompleteFeatureTaskIdentityError(args)?.let { return it }
    val family = args.kind.workflowFamily()
    val stepId = args.currentStepId ?: family.definition.defaultInitialStepId
    val workflowId = generateWorkflowId(family.definition.workflowIdPrefix)
    val effectiveSessionId = resolveEffectiveSessionId(
      args.kind,
      args.sessionId,
      family.definition,
      workflowId,
    )
    WorkflowEngine.validateOpen(family.definition, stepId)?.let { error ->
      return WorkflowOpenResult.Error(workflowId, error)
    }
    val hasIdentityCoordinates = args.repositoryIdentity != null || args.governedSpecPath != null
    val executionIdentity = buildFeatureTaskExecutionIdentity(
      BuildFeatureTaskExecutionIdentityArgs(
        kind = args.kind,
        hasIdentityCoordinates = hasIdentityCoordinates,
        workflowId = workflowId,
        issueKey = args.issueKey,
        repositoryIdentity = args.repositoryIdentity,
        governedSpecPath = args.governedSpecPath,
        routeScope = args.routeScope,
      ),
    )
    return persistOpenedWorkflow(
      PersistOpenedWorkflowArgs(
        family = family,
        workflowId = workflowId,
        effectiveSessionId = effectiveSessionId,
        stepId = stepId,
        dbOverride = args.dbOverride,
        issueKey = args.issueKey,
        executionIdentity = executionIdentity,
        engine = engine,
        database = database,
      ),
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
        DecompositionRuntimeWriteArgs(
          existing = existing,
          input = input,
          workflowId = request.workflowId,
          validator = decompositionManifestValidator,
          fileStore = decompositionManifestFileStore,
          repoRoot = repositoryRoot.path,
          manifestWriter = decompositionManifestWriter,
        ),
      )
      val effectiveInput = runtimeInput.input.withGoalObservabilityArtifacts(
        existing = existing,
        workflowId = request.workflowId,
        validator = goalObservabilityEventValidator,
        gitOperations = gitOperations,
        repoRoot = repositoryRoot.path,
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
      DecompositionManifestProjectionSupport.requireWritten(
        decompositionManifestWriter.writeProjectionFromWorkflowState(
          repositoryRoot.path,
          artifactsJson,
          decompositionManifestValidator,
          decompositionManifestFileStore,
        ),
        "Workflow update committed durable state but could not write its decomposition manifest projection.",
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

  fun repairFeatureTaskRuntimeIdentity(args: RepairFeatureTaskRuntimeIdentityArgs): WorkflowUpdateResult {
    val workflowId = args.workflowId
    val normalizedReason = args.reason.trim()
    if (normalizedReason.isEmpty() || normalizedReason.length > MAX_ABANDONMENT_REASON_LENGTH) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Identity-repair reason must contain 1..$MAX_ABANDONMENT_REASON_LENGTH characters.",
      )
    }
    val normalizedIssueKey = requireNotNull(normalizeIssueKey(args.issueKey)).uppercase()
    return database.transaction(args.dbOverride) { unitOfWork ->
      featureTaskIdentityRepair.repair(
        FeatureTaskIdentityRepairArgs(
          unitOfWork = unitOfWork,
          workflowId = workflowId,
          normalizedIssueKey = normalizedIssueKey,
          repositoryIdentity = args.repositoryIdentity,
          governedSpecPath = args.governedSpecPath,
          normalizedReason = normalizedReason,
        ),
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
            repositoryRoot.path,
            decompositionManifestWriter,
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
        ContinueExistingWorkflowArgs(
          validator = decompositionManifestValidator,
          fileStore = decompositionManifestFileStore,
          repoRoot = repositoryRoot.path,
          manifestWriter = decompositionManifestWriter,
        ),
      ).also { continuation ->
        projectionArtifactsJson = continuation.projectionArtifactsJson ?: projectionArtifactsJson
      }.result
    }
    projectionArtifactsJson?.let { artifactsJson ->
      DecompositionManifestProjectionSupport.requireWritten(
        decompositionManifestWriter.writeProjectionFromWorkflowState(
          repositoryRoot.path,
          artifactsJson,
          decompositionManifestValidator,
          decompositionManifestFileStore,
        ),
        "Workflow continue committed durable state but could not write its decomposition manifest projection.",
      )
    }
    return result
  }
}
