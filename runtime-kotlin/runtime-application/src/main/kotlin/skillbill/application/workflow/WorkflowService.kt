package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.encodeDecompositionManifestMap
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
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
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
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random

@Inject
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
    val family = kind.workflowFamily()
    val stepId = currentStepId ?: family.definition.defaultInitialStepId
    val workflowId = generateWorkflowId(family.definition.workflowIdPrefix)
    WorkflowEngine.validateOpen(family.definition, stepId)?.let { error ->
      return WorkflowOpenResult.Error(workflowId, error)
    }
    return database.transaction(dbOverride) { unitOfWork ->
      val record = engine.openRecord(family.definition, workflowId, sessionId, stepId)
      family.saveRecord(
        unitOfWork.workflowStates,
        record.toRecord().copy(
          startedAt = null,
          issueKey = normalizeIssueKey(issueKey),
        ),
      )
      if (kind != WorkflowFamilyKind.VERIFY && repositoryIdentity != null && governedSpecPath != null) {
        val normalizedIssueKey = requireNotNull(normalizeIssueKey(issueKey)).uppercase()
        val identity = FeatureTaskExecutionIdentity(
          workflowId = workflowId,
          normalizedIssueKey = normalizedIssueKey,
          repositoryIdentity = repositoryIdentity,
          governedSpecPath = governedSpecPath,
          mode = if (kind == WorkflowFamilyKind.TASK_PROSE) {
            FeatureTaskWorkflowMode.PROSE
          } else {
            FeatureTaskWorkflowMode.RUNTIME
          },
          routeScope = routeScope,
        )
        unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(identity)
      }
      val saved = family.get(unitOfWork.workflowStates, workflowId) ?: record
      WorkflowOpenResult.Ok(
        workflowId = saved.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        snapshot = engine.snapshotView(family.definition, saved),
      )
    }
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

private const val DEFAULT_LIST_LIMIT: Int = 20
private const val WORKFLOW_ID_SUFFIX_LENGTH: Int = 4
private const val SUFFIX_CHARS: String = "abcdefghijklmnopqrstuvwxyz0123456789"

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
