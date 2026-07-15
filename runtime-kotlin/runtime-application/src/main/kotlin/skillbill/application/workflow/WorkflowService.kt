package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.application.goalrunner.GoalObservabilityArtifacts
import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowLatestResult
import skillbill.application.model.WorkflowListResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowResumeResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.model.WorkflowUpdateResult
import skillbill.application.normalizeIssueKey
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.NoopGoalObservabilityEventValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random

@Inject
@Suppress("TooManyFunctions")
class WorkflowService(
  private val database: DatabaseSessionFactory,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
) {
  // SKILL-52.3 Subtask 1: the workflow engine and the decomposition
  // manifest seams receive their schema-validator dependencies at the
  // application boundary as injected domain-owned ports. The application
  // owns this wiring so `runtime-domain` and `runtime-application` never
  // import the concrete schema validators (now owned by
  // `runtime-infra-fs`). The validator caches the compiled JSON Schema
  // instance, so a single shared engine amortises schema parse + compile
  // cost across every call.
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  @Suppress("LongParameterList")
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
    val effectiveSessionId = if (
      sessionId.isBlank() && kind == WorkflowFamilyKind.TASK_RUNTIME
    ) {
      "${family.definition.defaultSessionPrefix}-$workflowId"
    } else {
      sessionId
    }
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
      WorkflowOpenResult.Ok(
        workflowId = saved.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        snapshot = engine.snapshotView(family.definition, saved),
      )
    }
  }

  private fun hasIncompleteFeatureTaskIdentity(
    kind: WorkflowFamilyKind,
    hasIdentityCoordinates: Boolean,
    issueKey: String?,
    repositoryIdentity: String?,
    governedSpecPath: String?,
  ): Boolean = kind in FEATURE_TASK_FAMILY_KINDS &&
    hasIdentityCoordinates &&
    listOf(issueKey, repositoryIdentity, governedSpecPath).any { it == null }

  @Suppress("LongParameterList")
  private fun buildFeatureTaskExecutionIdentity(
    kind: WorkflowFamilyKind,
    hasIdentityCoordinates: Boolean,
    workflowId: String,
    issueKey: String?,
    repositoryIdentity: String?,
    governedSpecPath: String?,
    routeScope: FeatureTaskRouteScope,
  ): FeatureTaskExecutionIdentity? {
    if (kind !in FEATURE_TASK_FAMILY_KINDS || !hasIdentityCoordinates) return null
    val requiredRepositoryIdentity = requireNotNull(repositoryIdentity)
    val normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
      requireNotNull(issueKey),
      requiredRepositoryIdentity,
    )
    val mode = if (kind == WorkflowFamilyKind.TASK_PROSE) {
      FeatureTaskWorkflowMode.PROSE
    } else {
      FeatureTaskWorkflowMode.RUNTIME
    }
    return FeatureTaskExecutionIdentity(
      workflowId = workflowId,
      normalizedIssueKey = normalizedIssueKey,
      repositoryIdentity = requiredRepositoryIdentity,
      governedSpecPath = requireNotNull(governedSpecPath),
      mode = mode,
      routeScope = routeScope,
    ).also(FeatureTaskExecutionIdentityPolicy::validate)
  }

  @Suppress("LongParameterList")
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
      "Only prose and runtime feature-task workflows use execution identity."
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
      updateOk(family.definition, updated, effectiveInput, unitOfWork.dbPath.toString())
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
      val family = WorkflowFamily.TASK_RUNTIME
      val existing = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction WorkflowUpdateResult.Error(
          workflowId,
          "Unknown runtime workflow_id '$workflowId'.",
          unitOfWork.dbPath.toString(),
        )
      if (existing.workflowStatus in family.definition.terminalStatuses) {
        return@transaction WorkflowUpdateResult.Error(
          workflowId,
          "Runtime workflow '$workflowId' is already terminal with status '${existing.workflowStatus}'.",
          unitOfWork.dbPath.toString(),
        )
      }
      val input = WorkflowUpdateInput(
        workflowStatus = "abandoned",
        currentStepId = existing.currentStepId.orEmpty(),
        stepUpdates = null,
        artifactsPatch = mapOf(
          FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY to mapOf(
            "reason" to normalizedReason,
            "abandoned_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
          ),
        ),
        sessionId = "",
      )
      val updated = engine.updateRecord(family.definition, existing, input)
      family.save(unitOfWork.workflowStates, updated)
      updateOk(family.definition, updated, input, unitOfWork.dbPath.toString())
    }
  }

  fun retryBlockedFeatureTaskRuntimePhase(
    workflowId: String,
    phaseId: String,
    reason: String,
    dbOverride: String? = null,
  ): WorkflowUpdateResult {
    val normalizedReason = reason.trim()
    if (normalizedReason.isEmpty() || normalizedReason.length > MAX_ABANDONMENT_REASON_LENGTH) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Blocked-phase retry reason must contain 1..$MAX_ABANDONMENT_REASON_LENGTH characters.",
      )
    }
    val normalizedPhaseId = phaseId.trim()
    val family = WorkflowFamily.TASK_RUNTIME
    if (normalizedPhaseId !in family.definition.stepIds) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Unknown runtime phase '$normalizedPhaseId'. Allowed: ${family.definition.stepIds.joinToString()}.",
      )
    }
    val request = BlockedPhaseRetryRequest(workflowId, normalizedPhaseId, normalizedReason)
    return database.transaction(dbOverride) { unitOfWork ->
      retryBlockedFeatureTaskRuntimePhaseInTransaction(unitOfWork, request)
    }
  }

  private fun retryBlockedFeatureTaskRuntimePhaseInTransaction(
    unitOfWork: UnitOfWork,
    request: BlockedPhaseRetryRequest,
  ): WorkflowUpdateResult {
    val family = WorkflowFamily.TASK_RUNTIME
    val existing = family.get(unitOfWork.workflowStates, request.workflowId)
      ?: return WorkflowUpdateResult.Error(
        request.workflowId,
        "Unknown runtime workflow_id '${request.workflowId}'.",
        unitOfWork.dbPath.toString(),
      )
    if (existing.workflowStatus in family.definition.terminalStatuses) {
      return WorkflowUpdateResult.Error(
        request.workflowId,
        "Runtime workflow '${request.workflowId}' is already terminal with status '${existing.workflowStatus}'.",
        unitOfWork.dbPath.toString(),
      )
    }
    val artifacts = decodeWorkflowArtifacts(existing.artifactsJson)
    val phaseRecords = decodeFeatureTaskRuntimePhaseRecords(artifacts)
    val ledger = FeatureTaskRuntimePhaseLedgerDecoder.decode(artifacts)
    val blockedRecord = phaseRecords[request.phaseId]
      ?: return WorkflowUpdateResult.Error(
        request.workflowId,
        "Runtime workflow '${request.workflowId}' has no durable phase record for '${request.phaseId}'.",
        unitOfWork.dbPath.toString(),
      )
    return if (blockedRecord.status != "blocked") {
      WorkflowUpdateResult.Error(
        request.workflowId,
        "Runtime workflow '${request.workflowId}' phase '${request.phaseId}' is " +
          "'${blockedRecord.status}', not blocked.",
        unitOfWork.dbPath.toString(),
      )
    } else {
      persistBlockedPhaseRetry(
        unitOfWork,
        existing,
        request,
        BlockedPhaseRetryState(phaseRecords, ledger, blockedRecord),
      )
    }
  }

  private fun persistBlockedPhaseRetry(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    request: BlockedPhaseRetryRequest,
    state: BlockedPhaseRetryState,
  ): WorkflowUpdateResult {
    val updatedRecords = LinkedHashMap(state.phaseRecords).apply { remove(request.phaseId) }
    val retryEntry = FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
      sequenceNumber = (state.ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
      phaseId = request.phaseId,
      attemptCount = state.blockedRecord.attemptCount,
      resolvedAgentId = state.blockedRecord.resolvedAgentId,
    )
    val input = WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = request.phaseId,
      stepUpdates = listOf(
        mapOf(
          "step_id" to request.phaseId,
          "status" to "pending",
          "attempt_count" to 0,
        ),
      ),
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, record) -> record.toArtifactMap() },
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to
          (state.ledger.map { it.toArtifactMap() } + retryEntry.toArtifactMap()).takeLast(
            FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
          ),
        FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY to mapOf(
          "phase_id" to request.phaseId,
          "reason" to request.reason,
          "retried_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
          "previous_blocked_reason" to state.blockedRecord.blockedReason,
          "previous_blocked_record" to state.blockedRecord.toArtifactMap(),
        ),
      ),
      sessionId = "",
    )
    val family = WorkflowFamily.TASK_RUNTIME
    val updated = engine.updateRecord(family.definition, existing, input)
    family.save(unitOfWork.workflowStates, updated)
    return updateOk(family.definition, updated, input, unitOfWork.dbPath.toString())
  }

  @Suppress("LongMethod", "LongParameterList")
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
      val family = WorkflowFamily.TASK_RUNTIME
      val workflowRow = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)
        ?: return@transaction WorkflowUpdateResult.Error(
          workflowId,
          "Unknown runtime workflow_id '$workflowId'.",
          unitOfWork.dbPath.toString(),
        )
      val existing = requireNotNull(family.get(unitOfWork.workflowStates, workflowId))
      if (existing.workflowStatus in family.definition.terminalStatuses) {
        return@transaction WorkflowUpdateResult.Error(
          workflowId,
          "Runtime workflow '$workflowId' is already terminal with status '${existing.workflowStatus}'; " +
            "identity repair is only supported for nonterminal workflows.",
          unitOfWork.dbPath.toString(),
        )
      }
      val persistedIssueKey = workflowRow.issueKey?.let(::normalizeIssueKey)?.uppercase()
      if (persistedIssueKey != null && persistedIssueKey != normalizedIssueKey) {
        return@transaction WorkflowUpdateResult.Error(
          workflowId,
          "Runtime workflow '$workflowId' belongs to issue '$persistedIssueKey', not '$normalizedIssueKey'.",
          unitOfWork.dbPath.toString(),
        )
      }
      val identity = FeatureTaskExecutionIdentity(
        workflowId = workflowId,
        normalizedIssueKey = normalizedIssueKey,
        repositoryIdentity = repositoryIdentity,
        governedSpecPath = governedSpecPath,
        mode = FeatureTaskWorkflowMode.RUNTIME,
        routeScope = FeatureTaskRouteScope.STANDALONE,
      )
      FeatureTaskExecutionIdentityPolicy.validate(identity)
      unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(identity)
      val input = WorkflowUpdateInput(
        workflowStatus = existing.workflowStatus,
        currentStepId = existing.currentStepId.orEmpty(),
        stepUpdates = null,
        artifactsPatch = mapOf(
          FEATURE_TASK_RUNTIME_IDENTITY_REPAIR_ARTIFACT_KEY to mapOf(
            "reason" to normalizedReason,
            "repaired_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
            "repository_identity" to repositoryIdentity,
            "governed_spec_path" to governedSpecPath,
          ),
        ),
        sessionId = "",
      )
      val updated = engine.updateRecord(family.definition, existing, input)
      family.save(unitOfWork.workflowStates, updated)
      updateOk(family.definition, updated, input, unitOfWork.dbPath.toString())
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
      if (record == null && family == WorkflowFamily.IMPLEMENT) {
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
        decompositionManifestFileStore,
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

  private fun updateOk(
    definition: WorkflowDefinition,
    updated: WorkflowStateSnapshot,
    effectiveInput: WorkflowUpdateInput,
    dbPath: String,
  ): WorkflowUpdateResult.Ok {
    val snapshot = engine.snapshotView(definition, updated)
    return WorkflowUpdateResult.Ok(
      workflowId = updated.workflowId,
      dbPath = dbPath,
      acknowledgement = engine.updateAcknowledgementView(
        snapshot = snapshot,
        input = effectiveInput,
      ),
    )
  }
}

private fun WorkflowEngine.syncDecompositionParentRuntime(
  family: WorkflowFamily,
  updated: WorkflowStateSnapshot,
  workflowId: String,
  unitOfWork: UnitOfWork,
  validator: DecompositionManifestValidator,
) {
  val manifest = updated.decompositionRuntime(validator)
  if (family == WorkflowFamily.IMPLEMENT && manifest != null) {
    unitOfWork.workflowStates.findDecomposedParentWorkflowForRuntime(manifest, validator)
      ?.toSnapshot()
      ?.takeUnless { it.workflowId == workflowId }
      ?.let { parent -> persistParentDecompositionRuntime(parent, manifest, unitOfWork, validator) }
  }
}

private data class BlockedPhaseRetryRequest(
  val workflowId: String,
  val phaseId: String,
  val reason: String,
)

private data class BlockedPhaseRetryState(
  val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val blockedRecord: FeatureTaskRuntimePhaseRecord,
)

private const val DEFAULT_LIST_LIMIT: Int = 20
private const val MAX_ABANDONMENT_REASON_LENGTH: Int = 1000
private const val FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY: String = "operator_abandonment"
private const val FEATURE_TASK_RUNTIME_IDENTITY_REPAIR_ARTIFACT_KEY: String = "operator_identity_repair"
private const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY: String = "operator_block_retry"
private const val WORKFLOW_ID_SUFFIX_LENGTH: Int = 4
private val FEATURE_TASK_FAMILY_KINDS = setOf(WorkflowFamilyKind.TASK_PROSE, WorkflowFamilyKind.TASK_RUNTIME)
private const val INCOMPLETE_FEATURE_TASK_IDENTITY_ERROR =
  "Feature-task workflows must be opened through openFeatureTask with complete immutable execution identity."
private const val SUFFIX_CHARS: String = "abcdefghijklmnopqrstuvwxyz0123456789"

private fun decodeWorkflowArtifacts(artifactsJson: String): Map<String, Any?> =
  JsonSupport.parseObjectOrNull(artifactsJson)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    .orEmpty()

private fun decodeFeatureTaskRuntimePhaseRecords(
  artifacts: Map<String, Any?>,
): Map<String, FeatureTaskRuntimePhaseRecord> {
  val raw = JsonSupport.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY])
    ?: return emptyMap()
  return raw.mapValues { (_, value) ->
    FeatureTaskRuntimePhaseRecord.fromArtifactMap(
      JsonSupport.anyToStringAnyMap(value)
        ?: throw IllegalArgumentException("Feature-task-runtime phase record entry is malformed."),
    )
  }
}

private object FeatureTaskRuntimePhaseLedgerDecoder {
  fun decode(artifacts: Map<String, Any?>): List<FeatureTaskRuntimePhaseLedgerEntry> {
    if (FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY !in artifacts) return emptyList()
    val raw = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as? List<*>
      ?: invalid("must decode to a JSON array")
    return raw.map { value ->
      val entry = JsonSupport.anyToStringAnyMap(value) ?: invalid("contains a malformed entry")
      try {
        FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entry)
      } catch (error: InvalidWorkflowStateSchemaError) {
        rethrow(error)
      } catch (error: IllegalArgumentException) {
        invalid("contains a malformed entry", error)
      }
    }
  }

  private fun invalid(reason: String, cause: Throwable? = null): Nothing = throw InvalidWorkflowStateSchemaError(
    "Workflow artifact '$FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY' $reason.",
    cause,
  )

  private fun rethrow(error: InvalidWorkflowStateSchemaError): Nothing = throw error
}

private fun WorkflowUpdateRequest.toWorkflowUpdateInput(): WorkflowUpdateInput = WorkflowUpdateInput(
  workflowStatus = workflowStatus,
  currentStepId = currentStepId,
  stepUpdates = stepUpdates,
  artifactsPatch = artifactsPatch,
  sessionId = sessionId,
)

internal fun skillbill.workflow.model.WorkflowContinueDecision.toReopenInput(sessionId: String): WorkflowUpdateInput =
  WorkflowUpdateInput(
    workflowStatus = "running",
    currentStepId = resumeStepId,
    stepUpdates =
    listOf(
      mapOf(
        "step_id" to resumeStepId,
        "status" to "running",
        "attempt_count" to nextAttemptCount,
      ),
    ),
    artifactsPatch = null,
    sessionId = sessionId,
  )

internal fun WorkflowFamily.withDecompositionRuntime(
  existing: WorkflowStateSnapshot,
  input: WorkflowUpdateInput,
  workflowId: String,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestFileStore,
): DecompositionRuntimeInput = if (this != WorkflowFamily.IMPLEMENT) {
  DecompositionRuntimeInput(input = input, updated = false)
} else {
  DecompositionManifestWriter.manifestFromWorkflowUpdate(
    repoRoot = Path.of("").toAbsolutePath(),
    existingArtifactsJson = existing.artifactsJson,
    artifactsPatch = input.artifactsPatch,
    validator = validator,
    runtimeUpdate = DecompositionManifestRuntimeUpdate(
      workflowId = workflowId,
      workflowStatus = input.workflowStatus,
      currentStepId = input.currentStepId,
      stepUpdates = input.stepUpdates,
    ),
    fileStore = fileStore,
  )?.let { manifest ->
    DecompositionRuntimeInput(
      input = input.copy(
        artifactsPatch = LinkedHashMap(input.artifactsPatch.orEmpty()).apply {
          put(
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
            encodeDecompositionManifestMap(manifest, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
          )
        },
      ),
      updated = true,
    )
  } ?: DecompositionRuntimeInput(input = input, updated = false)
}

internal data class DecompositionRuntimeInput(
  val input: WorkflowUpdateInput,
  val updated: Boolean,
)

private fun WorkflowUpdateInput.withGoalObservabilityArtifacts(
  existing: WorkflowStateSnapshot,
  workflowId: String,
  validator: GoalObservabilityEventValidator,
  gitOperations: WorkflowGitOperations,
): WorkflowUpdateInput {
  val patch = artifactsPatch
  return if (patch?.containsKey("progress_event") != true) {
    this
  } else {
    val existingArtifacts = JsonSupport.parseObjectOrNull(existing.artifactsJson)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      .orEmpty()
    val mergedArtifacts = LinkedHashMap(existingArtifacts).apply { putAll(patch) }
    val observabilityPatch = GoalObservabilityArtifacts.patchForProgressEvent(
      input = GoalObservabilityArtifacts.ProgressInput(
        artifacts = mergedArtifacts,
        workflowId = workflowId,
        workflowStatus = workflowStatus,
        currentStepId = currentStepId,
        worktreeActivity = gitOperations.worktreeActivity(Path.of("").toAbsolutePath().normalize())
          .takeIf { activity -> activity.ok }
          ?.let { activity ->
            GoalObservabilityArtifacts.GoalObservabilityWorktreeActivity(
              changedFileSummary = activity.changedFileSummary,
              diffStat = activity.diffStat,
            )
          },
      ),
      validator = validator,
    )
    observabilityPatch?.let { copy(artifactsPatch = LinkedHashMap(patch).apply { putAll(it) }) } ?: this
  }
}

internal fun generateWorkflowId(prefix: String): String {
  val now = OffsetDateTime.now(ZoneOffset.UTC)
  val suffix = (1..WORKFLOW_ID_SUFFIX_LENGTH).map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }
    .joinToString("")
  return "$prefix-${now.year}${now.monthValue.twoDigits()}${now.dayOfMonth.twoDigits()}-" +
    "${now.hour.twoDigits()}${now.minute.twoDigits()}${now.second.twoDigits()}-$suffix"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

internal fun WorkflowFamilyKind.workflowFamily(): WorkflowFamily = when (this) {
  WorkflowFamilyKind.TASK_PROSE -> WorkflowFamily.IMPLEMENT
  WorkflowFamilyKind.VERIFY -> WorkflowFamily.VERIFY
  WorkflowFamilyKind.TASK_RUNTIME -> WorkflowFamily.TASK_RUNTIME
}

internal enum class WorkflowFamily(
  val definition: WorkflowDefinition,
  val humanName: String,
  // Loop-only steps sit in the pipeline only as backward-edge destinations (e.g. the runtime's
  // `implement_fix`), so the forward transition skips them. The resume-boundary scan must skip them
  // too, or it parks a reconciled row at a loop-only phase its verdict never triggered. Empty for
  // families with a strict forward pipeline, leaving their boundary resolution unchanged.
  val loopOnlyStepIds: Set<String> = emptySet(),
) {
  IMPLEMENT(FeatureImplementWorkflowDefinition.definition, "feature-task-prose"),
  VERIFY(FeatureVerifyWorkflowDefinition.definition, "feature-verify"),
  TASK_RUNTIME(
    FeatureTaskRuntimePhaseWorkflowDefinition.definition,
    "feature-task-runtime",
    FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds,
  ),
  ;

  init {
    // Invariant mirrored from FeatureTaskRuntimeTransitionDeclaration: a family's loop-only steps
    // must be a subset of its own definition. Non-runtime families keep the empty default; this
    // guards a future family from declaring a loop-only step the definition — and the resume-boundary
    // scan that filters on it — would never recognize.
    require(loopOnlyStepIds.all { it in definition.stepIds }) {
      "WorkflowFamily $humanName declares loop-only steps absent from its definition: " +
        "${loopOnlyStepIds - definition.stepIds.toSet()}"
    }
  }

  fun save(repository: WorkflowStateRepository, record: WorkflowStateSnapshot) {
    saveRecord(repository, record.toRecord())
  }

  fun saveRecord(repository: WorkflowStateRepository, record: WorkflowStateRecord) {
    when (this) {
      IMPLEMENT -> repository.saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.PROSE)
      VERIFY -> repository.saveFeatureVerifyWorkflow(record)
      TASK_RUNTIME -> repository.saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.RUNTIME)
    }
  }

  fun get(repository: WorkflowStateRepository, workflowId: String): WorkflowStateSnapshot? = when (this) {
    IMPLEMENT -> repository.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.PROSE)
    VERIFY -> repository.getFeatureVerifyWorkflow(workflowId)
    TASK_RUNTIME -> repository.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
  }?.toSnapshot()

  fun getAll(repository: WorkflowStateRepository, workflowIds: Set<String>): Map<String, WorkflowStateSnapshot> =
    buildMap {
      workflowIds.chunked(WORKFLOW_SNAPSHOT_BATCH_SIZE).forEach { batch ->
        val records = when (this@WorkflowFamily) {
          IMPLEMENT -> repository.getFeatureImplementWorkflows(batch.toSet())
          VERIFY -> repository.getFeatureVerifyWorkflows(batch.toSet())
          TASK_RUNTIME -> repository.getFeatureTaskRuntimeWorkflows(batch.toSet())
        }
        records.forEach { (workflowId, record) -> put(workflowId, record.toSnapshot()) }
      }
    }

  fun list(repository: WorkflowStateRepository, limit: Int): List<WorkflowStateSnapshot> = when (this) {
    IMPLEMENT -> repository.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.PROSE, limit)
    VERIFY -> repository.listFeatureVerifyWorkflows(limit)
    TASK_RUNTIME -> repository.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, limit)
  }.map(WorkflowStateRecord::toSnapshot)

  fun latest(repository: WorkflowStateRepository): WorkflowStateSnapshot? = when (this) {
    IMPLEMENT -> repository.latestFeatureTaskWorkflow(FeatureTaskWorkflowMode.PROSE)
    VERIFY -> repository.latestFeatureVerifyWorkflow()
    TASK_RUNTIME -> repository.latestFeatureTaskWorkflow(FeatureTaskWorkflowMode.RUNTIME)
  }?.toSnapshot()

  /**
   * SKILL-52.1 open-boundary: durable workflow session summary lookup.
   * Returns the raw repository-supplied map verbatim; the typed
   * [skillbill.workflow.model.WorkflowContinueView.sessionSummary]
   * carries it through to the wire-shape map.
   */
  @OpenBoundaryMap("Durable workflow session summary passthrough")
  fun sessionSummary(repository: WorkflowStateRepository, sessionId: String): Map<String, Any?> {
    if (sessionId.isBlank()) {
      return emptyMap()
    }
    return when (this) {
      IMPLEMENT -> repository.getFeatureImplementSessionSummary(sessionId)?.toPayload().orEmpty()
      VERIFY -> repository.getFeatureVerifySessionSummary(sessionId)?.toPayload().orEmpty()
      // The runtime family has no session-summary record.
      TASK_RUNTIME -> emptyMap()
    }
  }
}

private const val WORKFLOW_SNAPSHOT_BATCH_SIZE = 900
