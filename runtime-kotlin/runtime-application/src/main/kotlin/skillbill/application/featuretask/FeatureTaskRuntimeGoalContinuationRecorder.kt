package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.application.featuretask.model.GoalSubtaskReviewPassReservation
import skillbill.application.featuretask.model.PortableUnreachableReviewBaseRecovery
import skillbill.application.featuretask.model.RemediationBaseCoherenceResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
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
  unreachableReviewBaseRecovery: PortableUnreachableReviewBaseRecovery,
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
    engine,
    unreachableReviewBaseRecovery,
  )
  val remediationReconciler = FeatureTaskRuntimeRemediationBaseReconciler(database, patcher, clock)

  internal fun recordGoalContinuationState(
    request: GoalContinuationStateRecordRequest,
    dbOverride: String? = null,
  ): Boolean = reviewStateRecorder.recordGoalContinuationState(request, dbOverride)

  fun reserveGoalReviewPass(workflowId: String, dbOverride: String? = null): GoalSubtaskReviewPassReservation =
    reviewPassRecorder.reserveGoalReviewPass(workflowId, dbOverride)

  fun persistGoalReviewInput(
    workflowId: String,
    input: GoalSubtaskReviewInput,
    dbOverride: String? = null,
  ): GoalSubtaskReviewState? = reviewPassRecorder.persistGoalReviewInput(workflowId, input, dbOverride)

  fun updateReviewState(
    workflowId: String,
    dbOverride: String? = null,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = reviewPassRecorder.updateReviewState(workflowId, dbOverride, transform)

  internal fun completeGoalReviewPass(
    request: GoalReviewPassCompletionRequest,
    dbOverride: String? = null,
  ): GoalSubtaskReviewState? = reviewPassRecorder.completeGoalReviewPass(request, dbOverride)

  class GoalReviewInputScope(
    val dbOverride: String? = null,
    val scopedUntrackedExclusions: List<String>? = null,
    val ownedPathspec: List<String> = emptyList(),
  )

  fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: GoalReviewInputScope = GoalReviewInputScope(),
  ): GoalSubtaskReviewInputPreparation = inputBuilder.buildGoalReviewInput(workflowId, gitOperations, repoRoot, scope)
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

fun FeatureTaskRuntimeGoalContinuationRecorder.reviewState(
  workflowId: String,
  dbOverride: String?,
): GoalSubtaskReviewState? = reviewStateRecorder.reviewState(workflowId, dbOverride)

fun FeatureTaskRuntimeGoalContinuationRecorder.continuation(
  workflowId: String,
  dbOverride: String?,
): FeatureTaskRuntimeGoalContinuationArtifact? = reviewStateRecorder.continuation(workflowId, dbOverride)

fun FeatureTaskRuntimeGoalContinuationRecorder.lastGoalReviewResult(workflowId: String, dbOverride: String?): String? =
  reviewPassRecorder.lastGoalReviewResult(workflowId, dbOverride)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
  workflowId: String,
  signal: RemediationDegradationSignal,
  dbOverride: String?,
) = remediationReconciler.appendRemediationRollbackDegradationEvidence(workflowId, signal, dbOverride)

fun FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence(
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  dbOverride: String?,
): RemediationBaseCoherenceResult =
  remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOperations, repoRoot, dbOverride)
