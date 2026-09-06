package skillbill.infrastructure.sqlite.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.db.decomposition.decodeArtifacts
import skillbill.db.goalrunner.authoritativeOutcomesBySubtask
import skillbill.db.goalrunner.goalReviewArtifacts
import skillbill.db.goalrunner.taskRuntimeRecordOrNull
import skillbill.db.goalrunner.validatedGoalReviewPasses
import skillbill.db.goalrunner.workflowFamilyFor
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeRepairRequest
import skillbill.ports.goalrunner.persistence.model.GoalSubtaskIdentity
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerReviewOutcomeStore
import skillbill.ports.goalrunner.runner.GoalRunnerTerminalOutcomeStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.get
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.save
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import java.nio.file.Path
import java.time.Clock

internal data class WorkflowGoalRunnerOutcomeStoreBridges(
  val workflow: GoalRunnerWorkflowOutcomeStore,
  val ledger: GoalRunnerAttemptLedgerStore,
  val childRepair: GoalRunnerChildRepairStore,
)

class WorkflowGoalRunnerOutcomeStoreBridgeBuilder @Inject constructor(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  private val goalProgressEventValidator: GoalProgressEventValidator,
  private val gitOperations: WorkflowGitOperations,
  private val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
) {
  internal fun build(
    decompositionManifestValidator: DecompositionManifestValidator?,
    decompositionManifestStore: DecompositionManifestStore,
    decompositionManifestWriter: DecompositionManifestProjectionWriter,
    childRepairExecutor: GoalRunnerChildRepairRunnerPort,
  ): WorkflowGoalRunnerOutcomeStoreBridges {
    val engine = WorkflowEngine(workflowSnapshotValidator)
    val blockWrites = WorkflowGoalRunnerBlockWrites(engine)
    val terminalPersistence = WorkflowGoalRunnerOutcomeTerminalPersistence(
      engine,
      gitOperations,
      workerSupervisor,
      clock,
    )
    val outcomeReconcile = WorkflowGoalRunnerOutcomeReconcile(
      engine,
      gitOperations,
      goalObservabilityEventValidator,
      blockWrites,
      terminalPersistence,
      clock,
    )
    val progressRecording = WorkflowGoalRunnerProgressRecording(
      database,
      engine,
      goalObservabilityEventValidator,
      goalProgressEventValidator,
    )
    val progressBridge = WorkflowGoalRunnerProgressBridge(progressRecording)
    val workflowBridge = WorkflowGoalRunnerOutcomeWorkflowBridge(
      terminal = WorkflowGoalRunnerTerminalBridge(database, terminalPersistence, gitOperations),
      review = WorkflowGoalRunnerReviewBridge(database, engine, phaseOutputValidator),
      reconcile = WorkflowGoalRunnerReconcileBridge(database, outcomeReconcile),
      blocks = WorkflowGoalRunnerBlockBridge(database, blockWrites),
      progress = progressBridge,
    )
    val childRepairBridge = WorkflowGoalRunnerChildRepairBridge(
      database,
      childRepairExecutor,
      decompositionManifestValidator,
      decompositionManifestStore,
      decompositionManifestWriter,
    )
    return WorkflowGoalRunnerOutcomeStoreBridges(
      workflow = workflowBridge,
      ledger = progressBridge,
      childRepair = childRepairBridge,
    )
  }
}

internal class WorkflowGoalRunnerChildRepairBridge(
  private val database: DatabaseSessionFactory,
  private val childRepair: GoalRunnerChildRepairRunnerPort,
  private val decompositionManifestValidator: DecompositionManifestValidator?,
  private val decompositionManifestStore: DecompositionManifestStore,
  private val decompositionManifestWriter: DecompositionManifestProjectionWriter,
) : GoalRunnerChildRepairStore {
  override fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis =
    database.read { unitOfWork ->
      childRepair.diagnose(
        workflowStates = unitOfWork.workflowStates,
        workflowId = request.workflowId,
        issueKey = request.issueKey,
        subtaskId = request.subtaskId,
        repoRoot = request.repoRoot,
      )
    }

  override fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult {
    val result = database.transaction { unitOfWork ->
      childRepair.apply(
        GoalRunnerChildRepairApplyRequest(
          unitOfWork = unitOfWork,
          workflowId = request.workflowId,
          issueKey = request.issueKey,
          subtaskId = request.subtaskId,
          wedgeClasses = request.wedgeClasses,
          repoRoot = request.repoRoot,
        ),
      )
    }
    result.manifestProjectionArtifactsJson?.let { artifactsJson ->
      val validator = decompositionManifestValidator ?: return@let
      checkNotNull(
        decompositionManifestWriter.writeProjectionFromWorkflowState(
          repoRoot = request.repoRoot,
          artifactsJson = artifactsJson,
          validator = validator,
          fileStore = decompositionManifestStore,
        ),
      ) {
        "Goal repair reopened the durable goal child but could not write its decomposition manifest projection."
      }
    }
    return result
  }
}

internal class WorkflowGoalRunnerReviewBridge(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
) : GoalRunnerReviewOutcomeStore {
  override fun goalSubtaskReviewState(workflowId: WorkflowId): GoalSubtaskReviewState? = database.read { unitOfWork ->
    val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId.value) ?: return@read null
    goalReviewArtifacts(decodeArtifacts(record.artifactsJson))?.state
  }

  override fun unemittedGoalReviewPasses(workflowId: WorkflowId): List<GoalSubtaskReviewPassResult> =
    database.read { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId.value) ?: return@read emptyList()
      val artifacts = decodeArtifacts(record.artifactsJson)
      if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY !in artifacts) return@read emptyList()
      val review = goalReviewArtifacts(artifacts) ?: return@read emptyList()
      validatedGoalReviewPasses(review, phaseOutputValidator, unitOfWork)
        .drop(review.state.emittedPassCount)
    }

  override fun acknowledgeGoalReviewPass(workflowId: WorkflowId, passNumber: Int): Boolean =
    database.transaction { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId.value) ?: return@transaction false
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
          sessionId = record.sessionId,
        ),
      )
      WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
      true
    }
}

internal class WorkflowGoalRunnerTerminalBridge(
  private val database: DatabaseSessionFactory,
  private val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  private val gitOperations: WorkflowGitOperations,
) : GoalRunnerTerminalOutcomeStore {
  override fun terminalOutcome(
    workflowId: WorkflowId,
    issueKey: IssueKey,
    subtaskId: SubtaskId,
  ): GoalRunnerStoredOutcome? = database.read { unitOfWork ->
    terminalPersistence.resolveTerminalOutcome(
      unitOfWork.workflowStates,
      workflowId.value,
      issueKey.value,
      subtaskId.value,
    ) { null }
  }

  override fun recoverAndPersistTerminalOutcome(
    workflowId: WorkflowId,
    issueKey: IssueKey,
    subtaskId: SubtaskId,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome? = database.transaction { unitOfWork ->
    terminalPersistence.displaceStaleBlockedContinuationOutcomeIfPresent(
      unitOfWork.workflowStates,
      workflowId.value,
      issueKey.value,
      subtaskId.value,
    )
    val resolved = terminalPersistence.resolveTerminalOutcome(
      unitOfWork.workflowStates,
      workflowId.value,
      issueKey.value,
      subtaskId.value,
    ) {
      gitOperations.headCommitSha(repoRoot).measuredCommitSha()
    } ?: return@transaction terminalPersistence.crashReconcileToResumable(
      unitOfWork.workflowStates,
      workflowId.value,
      issueKey.value,
      subtaskId.value,
    )
    val recovered = resolved.let { outcome ->
      terminalPersistence.recoverResolvedCommitPushBlock(
        workflowStates = unitOfWork.workflowStates,
        identity = GoalSubtaskIdentity(workflowId, issueKey, subtaskId),
        repoRoot = repoRoot,
        outcome = outcome,
      ) ?: outcome
    }
    recovered.also { outcome ->
      terminalPersistence.persistMeasuredCompletion(
        unitOfWork.workflowStates,
        workflowId.value,
        issueKey.value,
        subtaskId.value,
        outcome,
      )
    }
  }

  override fun recoverMissingResultPrefixOutput(
    workflowId: WorkflowId,
    issueKey: IssueKey,
    subtaskId: SubtaskId,
    output: Map<String, Any?>,
  ): GoalRunnerStoredOutcome? = database.transaction { unitOfWork ->
    val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId.value) ?: return@transaction null
    val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    terminalPersistence.recoverMissingResultPrefixTerminalOutcome(
      RecoverMissingResultPrefixTerminalOutcomeArgs(
        workflowStates = unitOfWork.workflowStates,
        family = family,
        record = record,
        output = output,
        issueKey = issueKey.value,
        subtaskId = subtaskId.value,
        workflowId = workflowId.value,
      ),
    )
  }
}

internal interface WorkflowGoalRunnerReconcileOutcomeStore {
  fun reconcileAuthoritativeOutcomes(
    issueKey: IssueKey,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun authoritativeOutcomes(issueKey: IssueKey): Map<Int, GoalRunnerStoredOutcome>
}

internal class WorkflowGoalRunnerReconcileBridge(
  private val database: DatabaseSessionFactory,
  private val outcomeReconcile: WorkflowGoalRunnerOutcomeReconcile,
) : WorkflowGoalRunnerReconcileOutcomeStore {
  override fun reconcileAuthoritativeOutcomes(
    issueKey: IssueKey,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> = database.transaction { unitOfWork ->
    outcomeReconcile.reconcileAuthoritativeOutcomesInTransaction(
      unitOfWork,
      issueKey.value,
      activeWorkflowIds,
      gate,
      repoRoot,
    )
  }

  override fun authoritativeOutcomes(issueKey: IssueKey): Map<Int, GoalRunnerStoredOutcome> =
    database.read { unitOfWork ->
      outcomeReconcile.loadContinuationCandidates(unitOfWork.workflowStates, issueKey.value.trim(), repoRoot = null)
        .authoritativeOutcomesBySubtask()
    }
}

internal interface WorkflowGoalRunnerBlockOutcomeStore {
  fun markBlocked(
    workflowId: WorkflowId,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
  ): String?

  fun reopenBlockedPhaseForOperatorResume(workflowId: WorkflowId, preferredPhaseId: String, reason: String): Boolean
}

internal class WorkflowGoalRunnerBlockBridge(
  private val database: DatabaseSessionFactory,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
) : WorkflowGoalRunnerBlockOutcomeStore {
  override fun markBlocked(
    workflowId: WorkflowId,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
  ): String? = database.transaction { unitOfWork ->
    blockWrites.markBlocked(
      workflowId.value,
      blockedReason,
      lastResumableStep,
      supervisionEvent,
      unitOfWork.workflowStates,
    )
  }

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: WorkflowId,
    preferredPhaseId: String,
    reason: String,
  ): Boolean = database.transaction { unitOfWork ->
    blockWrites.reopenBlockedPhaseForOperatorResume(unitOfWork, workflowId.value, preferredPhaseId, reason)
  }
}

internal interface WorkflowGoalRunnerProgressReadStore {
  fun progress(workflowId: WorkflowId): GoalRunnerWorkflowProgress?

  fun progressEvents(workflowId: WorkflowId): List<Map<String, Any?>>

  fun ledgerSequenceWatermarks(issueKey: IssueKey): GoalRunnerLedgerSequenceWatermarks

  fun childWorkflowLoopIterations(workflowId: WorkflowId): Map<String, Int>
}

internal interface WorkflowGoalRunnerProgressWriteStore {
  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean

  fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean

  fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: WorkflowId,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean
}

internal interface WorkflowGoalRunnerProgressOutcomeStore :
  WorkflowGoalRunnerProgressReadStore,
  WorkflowGoalRunnerProgressWriteStore

internal class WorkflowGoalRunnerProgressBridge(
  private val progressRecording: WorkflowGoalRunnerProgressRecording,
) : GoalRunnerAttemptLedgerStore,
  WorkflowGoalRunnerProgressOutcomeStore {
  override fun progress(workflowId: WorkflowId): GoalRunnerWorkflowProgress? =
    progressRecording.progress(workflowId.value)

  override fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean =
    progressRecording.recordObservabilityEvent(request)

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean =
    progressRecording.recordProgressEvent(request)

  override fun progressEvents(workflowId: WorkflowId): List<Map<String, Any?>> =
    progressRecording.progressEvents(workflowId.value)

  override fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean =
    progressRecording.recordAttemptLedgerEntry(request)

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: WorkflowId,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean = progressRecording.recordWorkerSubtaskRequestOutcomes(workflowId.value, outcomes)

  override fun ledgerSequenceWatermarks(issueKey: IssueKey): GoalRunnerLedgerSequenceWatermarks =
    progressRecording.ledgerSequenceWatermarks(issueKey.value)

  override fun childWorkflowLoopIterations(workflowId: WorkflowId): Map<String, Int> =
    progressRecording.childWorkflowLoopIterations(workflowId.value)

  override fun readAttemptLedgerSummary(issueKey: IssueKey): GoalRunnerAttemptLedgerSummary =
    progressRecording.readAttemptLedgerSummary(issueKey.value)
}

internal class WorkflowGoalRunnerOutcomeWorkflowBridge(
  terminal: GoalRunnerTerminalOutcomeStore,
  review: GoalRunnerReviewOutcomeStore,
  private val reconcile: WorkflowGoalRunnerReconcileOutcomeStore,
  private val blocks: WorkflowGoalRunnerBlockOutcomeStore,
  private val progress: WorkflowGoalRunnerProgressOutcomeStore,
) : GoalRunnerWorkflowOutcomeStore,
  GoalRunnerTerminalOutcomeStore by terminal,
  GoalRunnerReviewOutcomeStore by review,
  WorkflowGoalRunnerReconcileOutcomeStore by reconcile,
  WorkflowGoalRunnerBlockOutcomeStore by blocks,
  WorkflowGoalRunnerProgressOutcomeStore by progress {
  override fun authoritativeOutcomes(issueKey: IssueKey): Map<Int, GoalRunnerStoredOutcome> =
    reconcile.authoritativeOutcomes(issueKey)

  override fun progressEvents(workflowId: WorkflowId): List<Map<String, Any?>> = progress.progressEvents(workflowId)

  override fun childWorkflowLoopIterations(workflowId: WorkflowId): Map<String, Int> =
    progress.childWorkflowLoopIterations(workflowId)
}
