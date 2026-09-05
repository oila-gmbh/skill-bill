package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.persistence.authoritativeOutcomesBySubtask
import skillbill.ports.goalrunner.persistence.goalReviewArtifacts
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairDiagnoseRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeRepairRequest
import skillbill.ports.goalrunner.persistence.model.GoalSubtaskIdentity
import skillbill.ports.goalrunner.persistence.taskRuntimeRecordOrNull
import skillbill.ports.goalrunner.persistence.validatedGoalReviewPasses
import skillbill.ports.goalrunner.persistence.workflowFamilyFor
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerReviewOutcomeStore
import skillbill.ports.goalrunner.runner.GoalRunnerTerminalOutcomeStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import java.nio.file.Path

internal data class WorkflowGoalRunnerOutcomeStoreBridges(
  val workflow: GoalRunnerWorkflowOutcomeStore,
  val ledger: GoalRunnerAttemptLedgerStore,
  val childRepair: GoalRunnerChildRepairStore,
)

internal fun createWorkflowGoalRunnerOutcomeStoreBridges(
  args: CreateWorkflowGoalRunnerOutcomeStoreBridgesArgs,
): WorkflowGoalRunnerOutcomeStoreBridges {
  val engine = WorkflowEngine(args.workflowSnapshotValidator)
  val childRepair = args.childRepairExecutor
  val blockWrites = WorkflowGoalRunnerBlockWrites(engine)
  val terminalPersistence = WorkflowGoalRunnerOutcomeTerminalPersistence(
    engine,
    args.gitOperations,
    args.workerSupervisor,
    args.clock,
  )
  val outcomeReconcile = WorkflowGoalRunnerOutcomeReconcile(
    engine,
    args.gitOperations,
    args.goalObservabilityEventValidator,
    blockWrites,
    terminalPersistence,
    args.clock,
  )
  val progressRecording = WorkflowGoalRunnerProgressRecording(
    args.database,
    engine,
    args.goalObservabilityEventValidator,
    args.goalProgressEventValidator,
  )
  return workflowGoalRunnerOutcomeStoreBridges(
    WorkflowGoalRunnerOutcomeStoreBridgesArgs(
      database = args.database,
      engine = engine,
      gitOperations = args.gitOperations,
      phaseOutputValidator = args.phaseOutputValidator,
      decompositionManifestValidator = args.decompositionManifestValidator,
      decompositionManifestStore = args.decompositionManifestStore,
      outcomeReconcile = outcomeReconcile,
      blockWrites = blockWrites,
      terminalPersistence = terminalPersistence,
      progressRecording = progressRecording,
      childRepair = childRepair,
      decompositionManifestWriter = args.decompositionManifestWriter,
    ),
  )
}

private fun workflowGoalRunnerOutcomeStoreBridges(
  args: WorkflowGoalRunnerOutcomeStoreBridgesArgs,
): WorkflowGoalRunnerOutcomeStoreBridges {
  val terminalBridge = WorkflowGoalRunnerTerminalBridge(
    args.database,
    args.terminalPersistence,
    args.gitOperations,
  )
  val reviewBridge = WorkflowGoalRunnerReviewBridge(args.database, args.engine, args.phaseOutputValidator)
  val reconcileBridge = WorkflowGoalRunnerReconcileBridge(args.database, args.outcomeReconcile)
  val blockBridge = WorkflowGoalRunnerBlockBridge(args.database, args.blockWrites)
  val progressBridge = WorkflowGoalRunnerProgressBridge(args.progressRecording)
  val workflowBridge = WorkflowGoalRunnerOutcomeWorkflowBridge(
    terminal = terminalBridge,
    review = reviewBridge,
    reconcile = reconcileBridge,
    blocks = blockBridge,
    progress = progressBridge,
  )
  val childRepairBridge = WorkflowGoalRunnerChildRepairBridge(
    args.database,
    args.childRepair,
    args.decompositionManifestValidator,
    args.decompositionManifestStore,
    args.decompositionManifestWriter,
  )
  return WorkflowGoalRunnerOutcomeStoreBridges(
    workflow = workflowBridge,
    ledger = progressBridge,
    childRepair = childRepairBridge,
  )
}

internal class WorkflowGoalRunnerChildRepairBridge(
  private val database: DatabaseSessionFactory,
  private val childRepair: GoalRunnerChildRepairRunnerPort,
  private val decompositionManifestValidator: DecompositionManifestValidator?,
  private val decompositionManifestStore: DecompositionManifestStore,
  private val decompositionManifestWriter: DecompositionManifestProjectionWriter,
) : GoalRunnerChildRepairStore {
  override fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis =
    database.read(request.dbPathOverride) { unitOfWork ->
      childRepair.diagnose(
        GoalRunnerChildRepairDiagnoseRequest(
          workflowStates = unitOfWork.workflowStates,
          workflowId = request.workflowId,
          issueKey = request.issueKey,
          subtaskId = request.subtaskId,
          repoRoot = request.repoRoot,
          portableContext = request.portableContext,
        ),
      )
    }

  override fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult {
    val result = database.transaction(request.dbPathOverride) { unitOfWork ->
      childRepair.apply(
        GoalRunnerChildRepairApplyRequest(
          unitOfWork = unitOfWork,
          workflowId = request.workflowId,
          issueKey = request.issueKey,
          subtaskId = request.subtaskId,
          wedgeClasses = request.wedgeClasses,
          repoRoot = request.repoRoot,
          portableContext = request.portableContext,
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
  override fun goalSubtaskReviewState(workflowId: String, dbPathOverride: String?): GoalSubtaskReviewState? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@read null
      goalReviewArtifacts(decodeArtifacts(record.artifactsJson))?.state
    }

  override fun unemittedGoalReviewPasses(
    workflowId: String,
    dbPathOverride: String?,
  ): List<GoalSubtaskReviewPassResult> = database.read(dbPathOverride) { unitOfWork ->
    val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@read emptyList()
    val artifacts = decodeArtifacts(record.artifactsJson)
    if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY !in artifacts) return@read emptyList()
    val review = goalReviewArtifacts(artifacts) ?: return@read emptyList()
    validatedGoalReviewPasses(review, phaseOutputValidator, unitOfWork)
      .drop(review.state.emittedPassCount)
  }

  override fun acknowledgeGoalReviewPass(workflowId: String, passNumber: Int, dbPathOverride: String?): Boolean =
    database.transaction(dbPathOverride) { unitOfWork ->
      val record = taskRuntimeRecordOrNull(unitOfWork.workflowStates, workflowId) ?: return@transaction false
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
          sessionId = record.sessionId.orEmpty(),
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
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = database.read(dbPathOverride) { unitOfWork ->
    terminalPersistence.resolveTerminalOutcome(unitOfWork.workflowStates, workflowId, issueKey, subtaskId) { null }
  }

  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = database.transaction(dbPathOverride) { unitOfWork ->
    terminalPersistence.displaceStaleBlockedContinuationOutcomeIfPresent(
      unitOfWork.workflowStates,
      workflowId,
      issueKey,
      subtaskId,
    )
    val resolved = terminalPersistence.resolveTerminalOutcome(
      unitOfWork.workflowStates,
      workflowId,
      issueKey,
      subtaskId,
    ) {
      gitOperations.headCommitSha(repoRoot).measuredCommitSha()
    } ?: return@transaction terminalPersistence.crashReconcileToResumable(
      unitOfWork.workflowStates,
      workflowId,
      issueKey,
      subtaskId,
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
        workflowId,
        issueKey,
        subtaskId,
        outcome,
      )
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
    terminalPersistence.recoverMissingResultPrefixTerminalOutcome(
      RecoverMissingResultPrefixTerminalOutcomeArgs(
        workflowStates = unitOfWork.workflowStates,
        family = family,
        record = record,
        output = output,
        issueKey = issueKey,
        subtaskId = subtaskId,
        workflowId = workflowId,
      ),
    )
  }
}

internal interface WorkflowGoalRunnerReconcileOutcomeStore {
  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun authoritativeOutcomes(issueKey: String, dbPathOverride: String?): Map<Int, GoalRunnerStoredOutcome>
}

internal class WorkflowGoalRunnerReconcileBridge(
  private val database: DatabaseSessionFactory,
  private val outcomeReconcile: WorkflowGoalRunnerOutcomeReconcile,
) : WorkflowGoalRunnerReconcileOutcomeStore {
  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> = database.transaction(dbPathOverride) { unitOfWork ->
    outcomeReconcile.reconcileAuthoritativeOutcomesInTransaction(
      unitOfWork,
      issueKey,
      activeWorkflowIds,
      gate,
      repoRoot,
    )
  }

  override fun authoritativeOutcomes(issueKey: String, dbPathOverride: String?): Map<Int, GoalRunnerStoredOutcome> =
    database.read(dbPathOverride) { unitOfWork ->
      outcomeReconcile.loadContinuationCandidates(unitOfWork.workflowStates, issueKey.trim(), repoRoot = null)
        .authoritativeOutcomesBySubtask()
    }
}

internal interface WorkflowGoalRunnerBlockOutcomeStore {
  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    dbPathOverride: String?,
  ): String?

  fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String?,
  ): Boolean
}

internal class WorkflowGoalRunnerBlockBridge(
  private val database: DatabaseSessionFactory,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
) : WorkflowGoalRunnerBlockOutcomeStore {
  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    dbPathOverride: String?,
  ): String? = database.transaction(dbPathOverride) { unitOfWork ->
    blockWrites.markBlocked(
      workflowId,
      blockedReason,
      lastResumableStep,
      supervisionEvent,
      unitOfWork.workflowStates,
    )
  }

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    blockWrites.reopenBlockedPhaseForOperatorResume(unitOfWork, workflowId, preferredPhaseId, reason)
  }
}

internal interface WorkflowGoalRunnerProgressReadStore {
  fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress?

  fun progressEvents(workflowId: String, dbPathOverride: String?): List<Map<String, Any?>>

  fun ledgerSequenceWatermarks(issueKey: String, dbPathOverride: String?): GoalRunnerLedgerSequenceWatermarks

  fun childWorkflowLoopIterations(workflowId: String, dbPathOverride: String?): Map<String, Int>
}

internal interface WorkflowGoalRunnerProgressWriteStore {
  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest, dbPathOverride: String?): Boolean

  fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String?): Boolean

  fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest, dbPathOverride: String?): Boolean

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean
}

internal interface WorkflowGoalRunnerProgressOutcomeStore :
  WorkflowGoalRunnerProgressReadStore,
  WorkflowGoalRunnerProgressWriteStore

internal class WorkflowGoalRunnerProgressBridge(
  private val progressRecording: WorkflowGoalRunnerProgressRecording,
) : GoalRunnerAttemptLedgerStore,
  WorkflowGoalRunnerProgressOutcomeStore {
  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    progressRecording.progress(workflowId, dbPathOverride)

  override fun recordObservabilityEvent(
    request: GoalRunnerObservabilityRecordRequest,
    dbPathOverride: String?,
  ): Boolean = progressRecording.recordObservabilityEvent(request, dbPathOverride)

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String?): Boolean =
    progressRecording.recordProgressEvent(request, dbPathOverride)

  override fun progressEvents(workflowId: String, dbPathOverride: String?): List<Map<String, Any?>> =
    progressRecording.progressEvents(workflowId, dbPathOverride)

  override fun recordAttemptLedgerEntry(
    request: GoalRunnerAttemptLedgerRecordRequest,
    dbPathOverride: String?,
  ): Boolean = progressRecording.recordAttemptLedgerEntry(request, dbPathOverride)

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean = progressRecording.recordWorkerSubtaskRequestOutcomes(workflowId, outcomes, dbPathOverride)

  override fun ledgerSequenceWatermarks(
    issueKey: String,
    dbPathOverride: String?,
  ): GoalRunnerLedgerSequenceWatermarks = progressRecording.ledgerSequenceWatermarks(issueKey, dbPathOverride)

  override fun childWorkflowLoopIterations(workflowId: String, dbPathOverride: String?): Map<String, Int> =
    progressRecording.childWorkflowLoopIterations(workflowId, dbPathOverride)

  override fun readAttemptLedgerSummary(issueKey: String, dbPathOverride: String?): GoalRunnerAttemptLedgerSummary =
    progressRecording.readAttemptLedgerSummary(issueKey, dbPathOverride)
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
  override fun authoritativeOutcomes(issueKey: String, dbPathOverride: String?): Map<Int, GoalRunnerStoredOutcome> =
    reconcile.authoritativeOutcomes(issueKey, dbPathOverride)

  override fun progressEvents(workflowId: String, dbPathOverride: String?): List<Map<String, Any?>> =
    progress.progressEvents(workflowId, dbPathOverride)

  override fun childWorkflowLoopIterations(workflowId: String, dbPathOverride: String?): Map<String, Int> =
    progress.childWorkflowLoopIterations(workflowId, dbPathOverride)
}
