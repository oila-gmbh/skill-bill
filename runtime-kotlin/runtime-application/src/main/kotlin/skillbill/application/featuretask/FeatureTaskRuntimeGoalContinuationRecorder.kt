package skillbill.application.featuretask
import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.application.featuretask.model.GoalSubtaskReviewPassReservation
import skillbill.application.featuretask.model.RemediationBaseCoherenceResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import java.nio.file.Path
import java.time.Clock

@Inject
class FeatureTaskRuntimeGoalContinuationRecorder(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val diagnostics: RuntimeDiagnostics,
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

  fun reserveGoalReviewPass(workflowId: WorkflowId): GoalSubtaskReviewPassReservation =
    reviewPassRecorder.reserveGoalReviewPass(workflowId)

  fun persistGoalReviewInput(workflowId: WorkflowId, input: GoalSubtaskReviewInput): GoalSubtaskReviewState? =
    reviewPassRecorder.persistGoalReviewInput(workflowId, input)

  fun updateReviewState(
    workflowId: WorkflowId,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = reviewPassRecorder.updateReviewState(workflowId, transform)

  internal fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState? =
    reviewPassRecorder.completeGoalReviewPass(request)

  class GoalReviewInputScope(
    val scopedUntrackedExclusions: List<String>? = null,
    val ownedPathspec: List<String> = emptyList(),
  )

  fun buildGoalReviewInput(
    workflowId: WorkflowId,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: GoalReviewInputScope = GoalReviewInputScope(),
  ): GoalSubtaskReviewInputPreparation = inputBuilder.buildGoalReviewInput(workflowId, gitOperations, repoRoot, scope)
}

internal data class GoalContinuationStateRecordRequest(
  val workflowId: WorkflowId,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact? = null,
  val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  val outcome: FeatureTaskRuntimeGoalContinuationOutcome? = null,
  val fieldAdoption: FeatureTaskRuntimeGoalContinuationFieldAdoption? = null,
  val workflowStatus: String? = null,
)

internal data class GoalReviewPassCompletionRequest(
  val workflowId: WorkflowId,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
  val normalizedOutput: Map<String, Any?>,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
)

fun FeatureTaskRuntimeGoalContinuationRecorder.reviewState(workflowId: WorkflowId): GoalSubtaskReviewState? =
  reviewStateRecorder.reviewState(workflowId)

fun FeatureTaskRuntimeGoalContinuationRecorder.continuation(
  workflowId: WorkflowId,
): FeatureTaskRuntimeGoalContinuationArtifact? = reviewStateRecorder.continuation(workflowId)

fun FeatureTaskRuntimeGoalContinuationRecorder.lastGoalReviewResult(workflowId: WorkflowId): String? =
  reviewPassRecorder.lastGoalReviewResult(workflowId)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
  workflowId: WorkflowId,
  signal: RemediationDegradationSignal,
) = remediationReconciler.appendRemediationRollbackDegradationEvidence(workflowId, signal)

fun FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence(
  workflowId: WorkflowId,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): RemediationBaseCoherenceResult =
  remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOperations, repoRoot)
