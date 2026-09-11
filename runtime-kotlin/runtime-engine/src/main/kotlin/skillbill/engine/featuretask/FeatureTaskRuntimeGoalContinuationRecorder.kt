package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.model.RemediationBaseCoherenceResult
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.ports.db.DatabaseSessionFactoryimport skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

@Inject
class FeatureTaskRuntimeGoalContinuationRecorder(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  internal val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  private val patcher = FeatureTaskRuntimeGoalContinuationArtifactPatcher(engine)
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  val reviewStateRecorder = FeatureTaskRuntimeGoalContinuationStateRecorder(database, engine)
  val reviewPassRecorder = FeatureTaskRuntimeGoalReviewPassRecorder(database, patcher, runtimeOwnedPersistence)
  private val inputBuilder = FeatureTaskRuntimeGoalReviewInputBuilder(
    database,
    patcher,
    reviewPassRecorder::persistGoalReviewInput,
  )
  val remediationReconciler = FeatureTaskRuntimeRemediationBaseReconciler(database, patcher, clock)

  internal fun recordGoalContinuationState(request: GoalContinuationStateRecordRequest): Boolean =
    reviewStateRecorder.recordGoalContinuationState(request)

  fun reserveGoalReviewPass(workflowId: String): GoalSubtaskReviewPassReservation =
    reviewPassRecorder.reserveGoalReviewPass(workflowId)

  fun persistGoalReviewInput(workflowId: String, input: GoalSubtaskReviewInput): GoalSubtaskReviewState? =
    reviewPassRecorder.persistGoalReviewInput(workflowId, input)

  fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = reviewPassRecorder.updateReviewState(workflowId, transform)

  internal fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState? =
    reviewPassRecorder.completeGoalReviewPass(request)

  class GoalReviewInputScope(
    val scopedUntrackedExclusions: List<String>? = null,
    val ownedPathspec: List<String> = emptyList(),
  )
  fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: GoalReviewInputScope = GoalReviewInputScope(),
  ): GoalSubtaskReviewInputPreparation = runCatching {
    inputBuilder.buildGoalReviewInput(workflowId, gitOperations, repoRoot, scope)
  }.getOrElse { error ->
    if (error is CancellationException) throw error
    val refusal = error as? FeatureTaskRuntimeSubtaskCommitReconciliationError
      ?: FeatureTaskRuntimeSubtaskCommitReconciliationError(
        workflowId = workflowId,
        issueKey = "unknown",
        subtaskId = "unknown",
        reason = "review input could not be reconciled (${error.message}); " +
          "repair Git or workflow-store access before retrying",
        cause = error,
      )
    diagnostics.warning("record_kind=refusal seam=buildGoalReviewInput cause=${refusal.reason}", refusal)
    throw refusal
  }
}

internal data class GoalContinuationStateRecordRequest(
  val workflowId: String,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact? = null,
  val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  val outcome: FeatureTaskRuntimeGoalContinuationOutcome? = null,
  val fieldAdoption: FeatureTaskRuntimeGoalContinuationFieldAdoption? = null,
  val workflowStatus: String? = null,
)

internal data class GoalReviewPassCompletionRequest(
  val workflowId: String,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val normalizedOutput: Map<String, Any?>,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

fun FeatureTaskRuntimeGoalContinuationRecorder.reviewState(workflowId: String): GoalSubtaskReviewState? =
  reviewStateRecorder.reviewState(workflowId)

fun FeatureTaskRuntimeGoalContinuationRecorder.continuation(
  workflowId: String,
): FeatureTaskRuntimeGoalContinuationArtifact? = reviewStateRecorder.continuation(workflowId)

fun FeatureTaskRuntimeGoalContinuationRecorder.lastGoalReviewResult(workflowId: String): String? =
  reviewPassRecorder.lastGoalReviewResult(workflowId)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
  workflowId: String,
  signal: RemediationDegradationSignal,
) = remediationReconciler.appendRemediationRollbackDegradationEvidence(workflowId, signal)

fun FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence(
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): RemediationBaseCoherenceResult =
  remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOperations, repoRoot)