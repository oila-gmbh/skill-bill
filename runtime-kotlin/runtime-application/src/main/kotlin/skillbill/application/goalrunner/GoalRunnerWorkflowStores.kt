@file:Suppress("TooManyFunctions")

package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.loadManifestOrNull
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.findDecomposedParentWorkflow
import skillbill.application.workflow.generateWorkflowId
import skillbill.application.workflow.isActiveGoalRuntime
import skillbill.application.workflow.repoRoot
import skillbill.application.workflow.toSnapshot
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY
import skillbill.goalrunner.model.GOAL_SESSION_ACCOUNTING_LIMIT
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerProgressEvent
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.GoalProgressEventValidator
import skillbill.workflow.NoopGoalObservabilityEventValidator
import skillbill.workflow.NoopGoalProgressEventValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepState
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.model.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// SKILL-87: under requireStalenessEvidence, a running candidate counts as alive (not stale) when any
// declared-liveness or snapshot-update signal lands within this window. Generous enough that a long
// but legitimately quiet first phase is never mistaken for a dead subtask.
private const val STALENESS_EVIDENCE_WINDOW_MINUTES: Long = 30
private val STALENESS_EVIDENCE_WINDOW: Duration = Duration.ofMinutes(STALENESS_EVIDENCE_WINDOW_MINUTES)
private const val GOAL_REVIEW_POLICY_ARTIFACT_KEY = "goal_review_policy"

@Inject
class WorkflowGoalRunnerManifestStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
) : GoalRunnerManifestStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val stored = loadFromWorkflowStore(issueKey, dbPathOverride)
    val projected = repoRoot?.let { root -> findProjectedManifest(root, issueKey) }
    if (shouldRefreshFromCompleteProjection(stored, projected)) {
      return saveWorkflowProjection(
        requireNotNull(stored).copy(manifest = requireNotNull(projected)),
        dbPathOverride,
      ).state
    }
    return stored ?: projected?.let { manifest ->
      importFromManifestProjection(manifest, dbPathOverride)
    }
  }

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
      Path.of("").toAbsolutePath(),
      saved.projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
    return saved.state
  }

  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String?,
  ): GoalRunnerManifestState {
    var projectionArtifactsJson: String? = null
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      val existingParent = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, state.parentWorkflowId)
        ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
          state.manifest.issueKey,
          decompositionManifestValidator,
        )?.toSnapshot()
        ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
      val parentUpdated = engine.updateRecord(
        WorkflowFamily.IMPLEMENT.definition,
        existingParent,
        WorkflowUpdateInput(
          workflowStatus = existingParent.workflowStatus,
          currentStepId = existingParent.currentStepId,
          stepUpdates = null,
          artifactsPatch = mapOf(
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
              state.manifest,
              decompositionManifestValidator,
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
            ),
          ),
          sessionId = existingParent.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, parentUpdated)
      check(WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, setup.workflowId) == null) {
        "Goal child workflow '${setup.workflowId}' already exists."
      }
      val openedChild = engine.openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        setup.workflowId,
        "${WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix}-${state.manifest.issueKey}",
        WorkflowFamily.TASK_RUNTIME.definition.defaultInitialStepId,
      )
      val childUpdated = engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        openedChild,
        WorkflowUpdateInput(
          workflowStatus = openedChild.workflowStatus,
          currentStepId = openedChild.currentStepId,
          stepUpdates = null,
          artifactsPatch = mapOf(
            FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
              issueKey = state.manifest.issueKey,
              subtaskId = setup.subtaskId,
              suppressPr = true,
              goalBranch = setup.goalBranch,
              parentWorkflowId = parentUpdated.workflowId,
              codeReviewMode = setup.codeReviewMode,
            ).toArtifactMap(),
            GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to GoalSubtaskReviewState.initial(
              reviewBaseSha = setup.reviewBaseline.reviewBaseSha,
              baselineUntrackedPaths = setup.reviewBaseline.baselineUntrackedPaths,
              codeReviewMode = setup.codeReviewMode,
            ).toArtifactMap(),
            "install_sync_result" to mapOf(
              "status" to "skipped",
              "reason" to "goal-continuation forbids installer, uninstall, and install-sync flows",
            ),
          ),
          sessionId = openedChild.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, childUpdated)
      val refreshedParent = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, parentUpdated.workflowId) ?: parentUpdated
      projectionArtifactsJson = refreshedParent.artifactsJson
      GoalRunnerManifestState(
        parentWorkflowId = refreshedParent.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = refreshedParent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
      )
    }
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      Path.of("").toAbsolutePath(),
      requireNotNull(projectionArtifactsJson),
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
    return saved
  }

  override fun reviewMode(parentWorkflowId: String, dbPathOverride: String?): skillbill.review.CodeReviewExecutionMode? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, parentWorkflowId) ?: return@read null
      reviewModeFromArtifacts(decodeArtifacts(record.artifactsJson))
    }

  override fun persistReviewMode(
    parentWorkflowId: String,
    mode: skillbill.review.CodeReviewExecutionMode,
    dbPathOverride: String?,
  ): skillbill.review.CodeReviewExecutionMode = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    val existing = reviewModeFromArtifacts(decodeArtifacts(record.artifactsJson))
    if (existing != null) {
      existing
    } else {
      val updated = engine.updateRecord(
        WorkflowFamily.IMPLEMENT.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = mapOf(GOAL_REVIEW_POLICY_ARTIFACT_KEY to mapOf("code_review_mode" to mode.wireValue)),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
      mode
    }
  }

  override fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerReviewPolicy? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, parentWorkflowId) ?: return@read null
      reviewPolicyFromArtifacts(decodeArtifacts(record.artifactsJson))
    }

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String?,
  ): GoalRunnerReviewPolicy = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    val existing = reviewPolicyFromArtifacts(decodeArtifacts(record.artifactsJson))
    if (existing != null) {
      existing
    } else {
      val updated = engine.updateRecord(
        WorkflowFamily.IMPLEMENT.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = mapOf(
            GOAL_REVIEW_POLICY_ARTIFACT_KEY to buildMap {
              put("code_review_mode", policy.codeReviewMode.wireValue)
              policy.parallelReviewAgent?.let { put("parallel_review_agent", it) }
            },
          ),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
      policy
    }
  }

  private fun saveWorkflowProjection(state: GoalRunnerManifestState, dbPathOverride: String?): SavedManifestProjection {
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
    return SavedManifestProjection(
      state = saved,
      projectionArtifactsJson = requireNotNull(projectionArtifactsJson),
    )
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
    manifest: DecompositionManifest,
    dbPathOverride: String?,
  ): GoalRunnerManifestState? {
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

private fun reviewModeFromArtifacts(artifacts: Map<String, Any?>): skillbill.review.CodeReviewExecutionMode? {
  return reviewPolicyFromArtifacts(artifacts)?.codeReviewMode
}

private fun reviewPolicyFromArtifacts(artifacts: Map<String, Any?>): GoalRunnerReviewPolicy? {
  val raw = artifacts[GOAL_REVIEW_POLICY_ARTIFACT_KEY] ?: return null
  val policy = JsonSupport.anyToStringAnyMap(raw)
    ?: error("Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' must be a map.")
  val mode = policy["code_review_mode"] as? String
    ?: error("Goal review policy artifact '$GOAL_REVIEW_POLICY_ARTIFACT_KEY' is missing code_review_mode.")
  val codeReviewMode = try {
    skillbill.review.CodeReviewExecutionMode.fromWire(mode)
  } catch (error: IllegalArgumentException) {
    throw IllegalStateException("Goal review policy artifact has invalid code_review_mode '$mode'.", error)
  }
  val parallelReviewAgent = when (val value = policy["parallel_review_agent"]) {
    null -> null
    is String -> value.takeIf(String::isNotBlank)
      ?: error("Goal review policy artifact has a blank parallel_review_agent.")
    else -> error("Goal review policy artifact parallel_review_agent must be a string.")
  }
  return GoalRunnerReviewPolicy(codeReviewMode, parallelReviewAgent)
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

private fun Path.mayContainIssueKey(repoRoot: Path, issueKey: String): Boolean =
  runCatching { repoRoot.relativize(this).toString() }
    .getOrElse { toString() }
    .contains(issueKey)

@Inject
class WorkflowGoalRunnerOutcomeStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalObservabilityEventValidator: GoalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
  private val goalProgressEventValidator: GoalProgressEventValidator = NoopGoalProgressEventValidator,
  // Ground-truth git read to recover the terminal commit SHA when an agent completes
  // commit_push under suppress_pr but omits the SHA. No-op default keeps artifact-only behavior.
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
) : GoalRunnerWorkflowOutcomeStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  override fun goalSubtaskReviewState(workflowId: String, dbPathOverride: String?): GoalSubtaskReviewState? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      decodeArtifacts(record.artifactsJson)[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY]
        ?.let(::goalReviewArtifactMap)
        ?.let(GoalSubtaskReviewState::fromArtifactMap)
    }

  override fun unemittedGoalReviewPasses(
    workflowId: String,
    dbPathOverride: String?,
  ): List<GoalSubtaskReviewPassResult> = database.read(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read emptyList()
    val artifacts = decodeArtifacts(record.artifactsJson)
    val state = artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY]
      ?.let(::goalReviewArtifactMap)
      ?.let(GoalSubtaskReviewState::fromArtifactMap)
    ?: return@read emptyList()
    state.passResults.drop(state.emittedPassCount)
  }

  override fun acknowledgeGoalReviewPass(
    workflowId: String,
    passNumber: Int,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val state = artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY]
      ?.let(::goalReviewArtifactMap)
      ?.let(GoalSubtaskReviewState::fromArtifactMap)
      ?: return@transaction false
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

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> = database.transaction(dbPathOverride) { unitOfWork ->
    val normalizedIssueKey = issueKey.trim()
    val activeSet = activeWorkflowIds.map(String::trim).filter(String::isNotBlank).toSet()
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
    resolveTerminalOutcome(unitOfWork.workflowStates, workflowId, issueKey, subtaskId) {
      gitOperations.headCommitSha(repoRoot).measuredCommitSha()
    }?.also { outcome ->
      persistMeasuredCompletion(unitOfWork.workflowStates, workflowId, issueKey, subtaskId, outcome)
    }
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
    resolveTerminalOutcome(unitOfWork.workflowStates, workflowId, issueKey, subtaskId) { null }
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

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    database.read(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@read null
      val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      engine.snapshotView(family.definition, record)
      val steps = decodeWorkflowSteps(record.stepsJson)
      val artifacts = decodeArtifacts(record.artifactsJson)
      val finishCompleted = steps.any { step -> step.stepId == "finish" && step.status == "completed" }
      val currentStep = if (record.workflowStatus == "completed" || finishCompleted) "finish" else record.currentStepId
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

  override fun recordSessionAccounting(
    request: GoalRunnerSessionAccountingRecordRequest,
    dbPathOverride: String?,
  ): Boolean = appendHistoryArtifact(
    HistoryArtifactAppend(
      workflowId = request.workflowId,
      latestKey = null,
      historyKey = GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY,
      retentionLimit = GOAL_SESSION_ACCOUNTING_LIMIT,
      entryMap = request.accounting.toArtifactMap(),
    ),
    dbPathOverride,
  )

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

  // SKILL-64 Subtask 3: shared bounded append-and-cap write seam for the new
  // declared-progress, session-accounting, and attempt-ledger artifacts. Each
  // appends one entry, prunes to retentionLimit, and optionally mirrors the
  // newest entry into a latest-event key.
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
      // semantics as GoalProgressHistory/GoalSessionAccountingHistory/
      // GoalAttemptLedger.append(); the inline append no longer diverges.
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
    var maxAccounting: Int? = null
    var maxProgress: Int? = null
    listOf(WorkflowFamily.IMPLEMENT, WorkflowFamily.TASK_RUNTIME).forEach { family ->
      family.list(unitOfWork.workflowStates, Int.MAX_VALUE).forEach { snapshot ->
        val artifacts = decodeArtifacts(snapshot.artifactsJson)
        if (goalContinuation(artifacts)?.issueKey != normalizedIssueKey) {
          return@forEach
        }
        maxLedger = maxHistorySequence(artifacts, GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY, maxLedger)
        maxAccounting = maxHistorySequence(artifacts, GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY, maxAccounting)
        maxProgress = maxHistorySequence(artifacts, GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY, maxProgress)
      }
    }
    GoalRunnerLedgerSequenceWatermarks(
      maxLedgerSequence = maxLedger,
      maxAccountingSequence = maxAccounting,
      maxProgressSequence = maxProgress,
    )
  }

  private fun loadContinuationCandidates(
    workflowStates: WorkflowStateRepository,
    issueKey: String,
    // SKILL-68: when present, a complete-without-SHA candidate may recover its SHA from measured
    // HEAD (the manifest-workflowId-independent heal). null keeps the read-only, no-measure path
    // for pure status callers.
    repoRoot: Path? = null,
  ): List<GoalContinuationCandidate> = listOf(WorkflowFamily.IMPLEMENT, WorkflowFamily.TASK_RUNTIME).flatMap { family ->
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
    // steps[] order (SKILL-85 subtask 1). The prose IMPLEMENT/VERIFY reconciliation keeps its
    // historical current-step fallback unchanged.
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

  private fun workflowFamilyFor(workflowStates: WorkflowStateRepository, workflowId: String): WorkflowFamily? {
    val featureTaskRow = workflowStates.getFeatureTaskWorkflow(workflowId)
    if (featureTaskRow != null) {
      return when (featureTaskRow.mode) {
        FeatureTaskWorkflowMode.RUNTIME -> WorkflowFamily.TASK_RUNTIME
        FeatureTaskWorkflowMode.PROSE, null -> WorkflowFamily.IMPLEMENT
      }
    }
    return if (workflowStates.getFeatureVerifyWorkflow(workflowId) != null) {
      WorkflowFamily.VERIFY
    } else {
      null
    }
  }
}

private data class GoalContinuation(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
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

private fun goalReviewArtifactMap(value: Any?): Map<String, Any?> = (value as? Map<*, *>)
  ?.entries
  ?.associate { (key, entryValue) ->
    val stringKey = key as? String ?: throw InvalidGoalSubtaskReviewStateSchemaError(
      sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
      fieldPath = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
      reason = "map keys must be strings.",
    )
    stringKey to entryValue
  }
  ?: throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    reason = "must be an object.",
  )

private fun terminalOutcomeFor(
  snapshot: WorkflowStateSnapshot,
  artifacts: Map<String, Any?>,
  goalContinuation: GoalContinuation,
  // Lazily measures HEAD only on the recovery path, so the cost is paid solely there.
  measuredCommitSha: () -> String? = { null },
): GoalRunnerStoredOutcome? = goalContinuationOutcome(
  artifacts = artifacts,
  issueKey = goalContinuation.issueKey,
  subtaskId = goalContinuation.subtaskId,
  suppressPr = goalContinuation.suppressPr,
)
  // SKILL-68: a stored complete-without-SHA outcome is NOT authoritative for the SHA decision; it
  // falls through to the measure branch so the recovery path can heal it. Stored non-complete
  // statuses and complete-WITH-SHA outcomes remain authoritative and short-circuit as before.
  ?.takeUnless { it.status == GoalRunnerTerminalStatus.COMPLETE && it.commitSha.isNullOrBlank() }
  ?.copy(workflowId = snapshot.workflowId) ?: run {
  val steps = decodeWorkflowSteps(snapshot.stepsJson)
  val commitSha = commitShaFrom(artifacts)
    ?: if (commitPushCompletedUnderSuppressPr(steps, goalContinuation.suppressPr)) measuredCommitSha() else null
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
  commitPushCompletedUnderSuppressPr(steps, suppressPr) ->
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
