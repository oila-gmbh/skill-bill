package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import java.nio.file.Path

@Inject
class WorkflowGoalRunnerOutcomeStore(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  goalObservabilityEventValidator: GoalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
  goalProgressEventValidator: GoalProgressEventValidator = NoopGoalProgressEventValidator,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator = ReviewRawOutputFallbackValidator,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  private val decompositionManifestValidator: DecompositionManifestValidator? = null,
  private val decompositionManifestFileStore: DecompositionManifestFileStore =
    UnavailableDecompositionManifestFileStore,
) : GoalRunnerWorkflowOutcomeStore, GoalRunnerAttemptLedgerStore, GoalRunnerChildRepairStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  private val childRepair = GoalRunnerChildRepairOperations(engine, gitOperations, decompositionManifestValidator)
  private val blockWrites = WorkflowGoalRunnerBlockWrites(engine)
  private val terminalPersistence = WorkflowGoalRunnerOutcomeTerminalPersistence(
    engine,
    gitOperations,
    workerSupervisor,
  )
  private val outcomeReconcile = WorkflowGoalRunnerOutcomeReconcile(
    engine,
    gitOperations,
    goalObservabilityEventValidator,
    blockWrites,
    terminalPersistence,
  )
  private val progressRecording = WorkflowGoalRunnerProgressRecording(
    database,
    engine,
    goalObservabilityEventValidator,
    goalProgressEventValidator,
  )

  override fun diagnoseChildWedges(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    subtasks: List<DecompositionSubtask>,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerChildWedgeDiagnosis = database.read(dbPathOverride) { unitOfWork ->
    childRepair.diagnose(
      workflowStates = unitOfWork.workflowStates,
      workflowId = workflowId,
      issueKey = issueKey,
      subtaskId = subtaskId,
      repoRoot = repoRoot,
    )
  }

  override fun applyChildWedgeRepairs(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    wedgeClasses: List<GoalRunnerWedgeClass>,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerChildRepairApplyResult {
    val result = database.transaction(dbPathOverride) { unitOfWork ->
      childRepair.apply(
        unitOfWork = unitOfWork,
        workflowId = workflowId,
        issueKey = issueKey,
        subtaskId = subtaskId,
        wedgeClasses = wedgeClasses,
        repoRoot = repoRoot,
      )
    }
    result.manifestProjectionArtifactsJson?.let { artifactsJson ->
      val validator = decompositionManifestValidator ?: return@let
      checkNotNull(
        DecompositionManifestWriter.writeProjectionFromWorkflowState(
          repoRoot = repoRoot,
          artifactsJson = artifactsJson,
          validator = validator,
          fileStore = decompositionManifestFileStore,
        ),
      ) {
        "Goal repair reopened the durable goal child but could not write its decomposition manifest projection."
      }
    }
    return result
  }

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

  @Suppress("LongMethod")
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
      unitOfWork.workflowStates,
      family,
      record,
      output,
      issueKey,
      subtaskId,
      workflowId,
    )
  }

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

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    progressRecording.progress(workflowId, dbPathOverride)

  override fun recordObservabilityEvent(
    request: GoalRunnerObservabilityRecordRequest,
    dbPathOverride: String?,
  ): Boolean = progressRecording.recordObservabilityEvent(request, dbPathOverride)

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String?): Boolean =
    progressRecording.recordProgressEvent(request, dbPathOverride)

  override fun recordAttemptLedgerEntry(
    request: GoalRunnerAttemptLedgerRecordRequest,
    dbPathOverride: String?,
  ): Boolean = progressRecording.recordAttemptLedgerEntry(request, dbPathOverride)

  override fun progressEvents(workflowId: String, dbPathOverride: String?): List<Map<String, Any?>> =
    progressRecording.progressEvents(workflowId, dbPathOverride)

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
