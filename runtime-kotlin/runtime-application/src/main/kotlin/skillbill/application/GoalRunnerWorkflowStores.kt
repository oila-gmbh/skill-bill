@file:Suppress("TooManyFunctions")

package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerProgressEvent
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.NoopGoalObservabilityEventValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepState
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.model.goalObservabilityLatestEventFromArtifacts
import java.nio.file.Path

@Inject
class WorkflowGoalRunnerManifestStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
) : GoalRunnerManifestStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    loadFromWorkflowStore(issueKey, dbPathOverride) ?: repoRoot?.let { root ->
      importFromManifestProjection(root, issueKey, dbPathOverride)
    }

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    var projectionArtifactsJson: String? = null
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      val existing = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, state.parentWorkflowId)
        ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
          state.manifest.issueKey,
          decompositionManifestValidator,
        )?.toSnapshot()
        ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
      val existingSnapshot = existing
      val updated = engine.updateRecord(
        WorkflowFamily.IMPLEMENT.definition,
        existingSnapshot,
        WorkflowUpdateInput(
          workflowStatus = existingSnapshot.workflowStatus,
          currentStepId = existingSnapshot.currentStepId,
          stepUpdates = null,
          artifactsPatch = mapOf(
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
              state.manifest,
              decompositionManifestValidator,
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
            ),
          ),
          sessionId = existingSnapshot.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
      val refreshed = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, updated.workflowId) ?: updated
      projectionArtifactsJson = refreshed.artifactsJson
      GoalRunnerManifestState(
        parentWorkflowId = refreshed.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = refreshed.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
      )
    }
    projectionArtifactsJson?.let { artifactsJson ->
      DecompositionManifestWriter.writeProjectionFromWorkflowState(
        Path.of("").toAbsolutePath(),
        artifactsJson,
        decompositionManifestValidator,
        decompositionManifestFileStore,
      )
    }
    return saved
  }

  private fun loadFromWorkflowStore(issueKey: String, dbPathOverride: String?): GoalRunnerManifestState? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = unitOfWork.workflowStates.findDecomposedParentWorkflow(issueKey, decompositionManifestValidator)
        ?: return@read null
      val snapshot = record.toSnapshot()
      val manifest = snapshot.decompositionRuntime(decompositionManifestValidator) ?: return@read null
      GoalRunnerManifestState(
        parentWorkflowId = snapshot.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = manifest,
      )
    }

  private fun importFromManifestProjection(
    repoRoot: Path,
    issueKey: String,
    dbPathOverride: String?,
  ): GoalRunnerManifestState? {
    val manifest = findProjectedManifest(repoRoot, issueKey) ?: return null
    val workflowId = generateWorkflowId(WorkflowFamily.IMPLEMENT.definition.workflowIdPrefix)
    return database.transaction(dbPathOverride) { unitOfWork ->
      val opened = engine.openRecord(
        WorkflowFamily.IMPLEMENT.definition,
        workflowId,
        WorkflowFamily.IMPLEMENT.definition.defaultSessionPrefix,
        "plan",
      )
      val imported = engine.updateRecord(
        WorkflowFamily.IMPLEMENT.definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "abandoned",
          currentStepId = "plan",
          stepUpdates = listOf(
            mapOf("step_id" to "assess", "status" to "completed", "attempt_count" to 1),
            mapOf("step_id" to "create_branch", "status" to "completed", "attempt_count" to 1),
            mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
            mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
          ),
          artifactsPatch = mapOf(
            "plan" to mapOf("mode" to "decompose"),
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
              manifest,
              decompositionManifestValidator,
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
            ),
          ),
          sessionId = opened.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, imported)
      val saved = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId) ?: imported
      GoalRunnerManifestState(
        parentWorkflowId = saved.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = saved.decompositionRuntime(decompositionManifestValidator) ?: manifest,
      )
    }
  }

  private fun findProjectedManifest(repoRoot: Path, issueKey: String) =
    decompositionManifestFileStore.findDecompositionManifestFiles(repoRoot)
      .asSequence()
      .sortedBy { path -> path.toString() }
      .filter { path -> path.mayContainIssueKey(repoRoot, issueKey) }
      .mapNotNull { path ->
        loadManifestOrNull(path, decompositionManifestValidator, decompositionManifestFileStore)
          ?.let { manifest -> ProjectedManifestCandidate(path, manifest) }
      }
      .filter { candidate -> candidate.manifest.issueKey == issueKey }
      .toList()
      .let { candidates ->
        val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
        if (activeCandidates.size > 1) {
          error(
            "Ambiguous checked-in decomposition manifests for '$issueKey': " +
              activeCandidates.joinToString { candidate -> repoRoot.relativize(candidate.path).toString() } +
              ". Pass an explicit workflow or manifest selector before continuing.",
          )
        }
        activeCandidates.firstOrNull()?.manifest ?: candidates.firstOrNull()?.manifest
      }
}

private data class ProjectedManifestCandidate(
  val path: Path,
  val manifest: DecompositionManifest,
)

private fun Path.mayContainIssueKey(repoRoot: Path, issueKey: String): Boolean =
  runCatching { repoRoot.relativize(this).toString() }
    .getOrElse { toString() }
    .contains(issueKey)

@Inject
class WorkflowGoalRunnerOutcomeStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalObservabilityEventValidator: GoalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
) : GoalRunnerWorkflowOutcomeStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    allowInactiveReconciliation: Boolean,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> = database.transaction(dbPathOverride) { unitOfWork ->
    val normalizedIssueKey = issueKey.trim()
    val activeSet = activeWorkflowIds.map(String::trim).filter(String::isNotBlank).toSet()
    val initialCandidates = loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey)
    val initialAuthoritative = initialCandidates.authoritativeOutcomesBySubtask()
    initialCandidates
      .filter { candidate ->
        if (candidate.snapshot.workflowStatus != "running") {
          return@filter false
        }
        val authoritative = initialAuthoritative[candidate.goalContinuation.subtaskId]
        val inactive = candidate.snapshot.workflowId !in activeSet
        val supersededByAuthoritative = authoritative?.status == GoalRunnerTerminalStatus.COMPLETE &&
          authoritative.workflowId != candidate.snapshot.workflowId
        (allowInactiveReconciliation && inactive) || supersededByAuthoritative
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
          record = stale.snapshot,
          blockedReason = blockedReason,
          lastResumableStep = stale.snapshot.currentStepId,
          workflowStates = unitOfWork.workflowStates,
          supervisionEvent = null,
        )
      }
    loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey)
      .authoritativeOutcomesBySubtask()
  }

  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = database.read(dbPathOverride) { unitOfWork ->
    val snapshot = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    engine.snapshotView(WorkflowFamily.IMPLEMENT.definition, snapshot)
    val artifacts = decodeArtifacts(snapshot.artifactsJson)
    val goalContinuation = goalContinuation(artifacts) ?: return@read null
    if (goalContinuation.issueKey != issueKey || goalContinuation.subtaskId != subtaskId) {
      return@read null
    }
    terminalOutcomeFor(snapshot, artifacts, goalContinuation)
  }

  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    dbPathOverride: String?,
  ): String? = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    markBlocked(record, blockedReason, lastResumableStep, unitOfWork.workflowStates, supervisionEvent)
  }

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      engine.snapshotView(WorkflowFamily.IMPLEMENT.definition, record)
      val steps = decodeWorkflowSteps(record.stepsJson)
      val artifacts = decodeArtifacts(record.artifactsJson)
      val finishCompleted = steps.any { step -> step.stepId == "finish" && step.status == "completed" }
      val currentStep = if (record.workflowStatus == "completed" || finishCompleted) "finish" else record.currentStepId
      val progressEvent = progressEventFrom(artifacts)
      val observabilityEvent = goalObservabilityLatestEventFromArtifacts(artifacts, goalObservabilityEventValidator)
      GoalRunnerWorkflowProgress(
        workflowId = record.workflowId,
        workflowStatus = record.workflowStatus,
        currentStepId = currentStep,
        progressToken = record.progressToken(),
        latestDurableProgressEvent = progressEvent,
        latestGoalObservabilityEvent = observabilityEvent?.toProgressEvent(),
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
    val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, request.workflowId)
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
      WorkflowFamily.IMPLEMENT.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = observabilityPatch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
    true
  }

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existing = (artifacts[WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY] as? List<*>)
      .orEmpty()
      .mapNotNull { item -> item as? Map<*, *> }
      .map { item -> JsonSupport.anyToStringAnyMap(item) }
    val updatedOutcomes = (existing + outcomes.map(GoalRunnerWorkerSubtaskRequestOutcome::toArtifactMap))
      .takeLast(WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT)
    val updated = engine.updateRecord(
      WorkflowFamily.IMPLEMENT.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = mapOf(WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY to updatedOutcomes),
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
    true
  }

  private fun loadContinuationCandidates(
    workflowStates: skillbill.ports.persistence.WorkflowStateRepository,
    issueKey: String,
  ): List<GoalContinuationCandidate> = WorkflowFamily.IMPLEMENT
    .list(workflowStates, Int.MAX_VALUE)
    .mapNotNull { snapshot ->
      engine.snapshotView(WorkflowFamily.IMPLEMENT.definition, snapshot)
      val artifacts = decodeArtifacts(snapshot.artifactsJson)
      val goalContinuation = goalContinuation(artifacts) ?: return@mapNotNull null
      if (goalContinuation.issueKey != issueKey) {
        return@mapNotNull null
      }
      GoalContinuationCandidate(
        snapshot = snapshot,
        goalContinuation = goalContinuation,
        outcome = terminalOutcomeFor(snapshot, artifacts, goalContinuation),
      )
    }

  private fun markBlocked(
    record: WorkflowStateSnapshot,
    blockedReason: String,
    lastResumableStep: String,
    workflowStates: skillbill.ports.persistence.WorkflowStateRepository,
    supervisionEvent: GoalRunnerSupervisionEvent?,
  ): String {
    val steps = decodeWorkflowSteps(record.stepsJson)
    val stepId = blockedStepId(record, steps, lastResumableStep)
    val attemptCount = steps.firstOrNull { it.stepId == stepId }?.attemptCount ?: 1
    val updated = engine.updateRecord(
      WorkflowFamily.IMPLEMENT.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = "blocked",
        currentStepId = stepId,
        stepUpdates = listOf(
          mapOf("step_id" to stepId, "status" to "blocked", "attempt_count" to attemptCount),
        ),
        artifactsPatch = buildMap {
          put("blocked_reason", blockedReason)
          supervisionEvent?.let { event -> put("supervision_event", event.toArtifactsMap()) }
        },
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.IMPLEMENT.save(workflowStates, updated)
    return stepId
  }
}

private data class GoalContinuation(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
)

private data class GoalContinuationCandidate(
  val snapshot: WorkflowStateSnapshot,
  val goalContinuation: GoalContinuation,
  val outcome: GoalRunnerStoredOutcome?,
)

private fun goalContinuation(artifacts: Map<String, Any?>): GoalContinuation? =
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
      )
    }
  }

private fun terminalOutcomeFor(
  snapshot: WorkflowStateSnapshot,
  artifacts: Map<String, Any?>,
  goalContinuation: GoalContinuation,
): GoalRunnerStoredOutcome? = goalContinuationOutcome(
  artifacts = artifacts,
  issueKey = goalContinuation.issueKey,
  subtaskId = goalContinuation.subtaskId,
  suppressPr = goalContinuation.suppressPr,
)?.copy(workflowId = snapshot.workflowId) ?: run {
  val steps = decodeWorkflowSteps(snapshot.stepsJson)
  val commitSha = commitShaFrom(artifacts)
  terminalStatus(snapshot, steps, goalContinuation.suppressPr, commitSha)?.let { status ->
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

private fun goalContinuationOutcome(
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

private fun goalContinuationTerminalStatus(status: String?): GoalRunnerTerminalStatus? = when (status) {
  "complete", "completed" -> GoalRunnerTerminalStatus.COMPLETE
  "failed" -> GoalRunnerTerminalStatus.FAILED
  "blocked" -> GoalRunnerTerminalStatus.BLOCKED
  "timeout", "timed_out" -> GoalRunnerTerminalStatus.TIMEOUT
  else -> null
}

private fun terminalStatus(
  snapshot: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  suppressPr: Boolean,
  commitSha: String?,
): GoalRunnerTerminalStatus? = when {
  suppressPr && steps.any { it.stepId == "commit_push" && it.status == "completed" } ->
    if (commitSha.isNullOrBlank()) {
      GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
    } else {
      GoalRunnerTerminalStatus.COMPLETE
    }
  snapshot.workflowStatus == "failed" || steps.any { it.status == "failed" } -> GoalRunnerTerminalStatus.FAILED
  snapshot.workflowStatus == "blocked" || steps.any { it.status == "blocked" } -> GoalRunnerTerminalStatus.BLOCKED
  snapshot.workflowStatus in setOf("completed", "abandoned") -> GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
  else -> null
}

private fun blockedReasonFrom(
  artifacts: Map<String, Any?>,
  steps: List<WorkflowStepState>,
  status: GoalRunnerTerminalStatus,
): String? = artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
  ?: steps.firstOrNull { it.status in setOf("failed", "blocked") }
    ?.let { step -> "Workflow step '${step.stepId}' is ${step.status}." }
  ?: "Workflow reached a terminal state without a goal-continuation commit SHA."
    .takeIf { status == GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME }

private fun commitShaFrom(artifacts: Map<String, Any?>): String? =
  (artifacts["commit_push_result"] as? Map<*, *>)?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)

private fun progressEventFrom(artifacts: Map<String, Any?>): GoalRunnerProgressEvent? =
  (artifacts["progress_event"] as? Map<*, *>)
    ?.toGoalRunnerProgressEventOrNull()

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

private fun decodeWorkflowSteps(stepsJson: String): List<WorkflowStepState> {
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

private fun blockedStepId(
  record: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  requestedStepId: String,
): String = requestedStepId.takeIf { stepId ->
  stepId.isNotBlank() && steps.firstOrNull { step -> step.stepId == stepId }?.status == "running"
}
  ?: steps.firstOrNull { step -> step.status == "running" }?.stepId
  ?: record.currentStepId.takeIf(String::isNotBlank)
  ?: requestedStepId.takeIf(String::isNotBlank)
  ?: "preplan"

private fun Any?.asGoalRunnerIntOrNull(): Int? = when (this) {
  is Int -> this
  is Number -> toInt()
  is String -> toIntOrNull()
  else -> null
}
