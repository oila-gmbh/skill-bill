package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.db.DatabaseSessionFactory
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

@Inject
class FeatureTaskRuntimeGoalContinuationRecorder(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  private val patcher = FeatureTaskRuntimeGoalContinuationArtifactPatcher(engine)
  private val runtimeOwnedPersistence = RuntimeOwnedPersistenceBoundary(database, diagnostics)
  private val stateRecorder = FeatureTaskRuntimeGoalContinuationStateRecorder(database, engine, patcher)
  private val passRecorder = FeatureTaskRuntimeGoalReviewPassRecorder(database, patcher, runtimeOwnedPersistence)
  private val inputBuilder = FeatureTaskRuntimeGoalReviewInputBuilder(
    database,
    patcher,
    passRecorder::persistGoalReviewInput,
  )
  private val remediationReconciler = FeatureTaskRuntimeRemediationBaseReconciler(database, patcher)

  internal fun recordGoalContinuationState(
    request: GoalContinuationStateRecordRequest,
    dbOverride: String? = null,
  ): Boolean = stateRecorder.recordGoalContinuationState(request, dbOverride)

  fun reviewState(workflowId: String, dbOverride: String? = null): GoalSubtaskReviewState? =
    stateRecorder.reviewState(workflowId, dbOverride)

  fun continuation(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeGoalContinuationArtifact? =
    stateRecorder.continuation(workflowId, dbOverride)

  internal fun reserveGoalReviewPass(
    workflowId: String,
    dbOverride: String? = null,
  ): GoalSubtaskReviewPassReservation = passRecorder.reserveGoalReviewPass(workflowId, dbOverride)

  fun persistGoalReviewInput(
    workflowId: String,
    input: GoalSubtaskReviewInput,
    dbOverride: String? = null,
  ): GoalSubtaskReviewState? = passRecorder.persistGoalReviewInput(workflowId, input, dbOverride)

  fun updateReviewState(
    workflowId: String,
    dbOverride: String? = null,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = passRecorder.updateReviewState(workflowId, dbOverride, transform)

  internal fun completeGoalReviewPass(
    request: GoalReviewPassCompletionRequest,
    dbOverride: String? = null,
  ): GoalSubtaskReviewState? = passRecorder.completeGoalReviewPass(request, dbOverride)

  internal class GoalReviewInputScope(
    val dbOverride: String? = null,
    val scopedUntrackedExclusions: List<String>? = null,
    val ownedPathspec: List<String> = emptyList(),
  )

  internal fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: GoalReviewInputScope = GoalReviewInputScope(),
  ): GoalSubtaskReviewInputPreparation = inputBuilder.buildGoalReviewInput(workflowId, gitOperations, repoRoot, scope)

  fun lastGoalReviewResult(workflowId: String, dbOverride: String? = null): String? =
    passRecorder.lastGoalReviewResult(workflowId, dbOverride)

  internal fun reconcileRemediationBaseCoherence(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    dbOverride: String? = null,
  ): RemediationBaseCoherenceResult =
    remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOperations, repoRoot, dbOverride)

  internal fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
    dbOverride: String?,
  ) = remediationReconciler.appendRemediationRollbackDegradationEvidence(workflowId, signal, dbOverride)
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

internal sealed interface GoalSubtaskReviewPassReservation {
  data object MissingState : GoalSubtaskReviewPassReservation
}

internal data class GoalSubtaskReviewPassReserved(val state: GoalSubtaskReviewState) : GoalSubtaskReviewPassReservation
internal data class GoalSubtaskReviewPassInFlight(val state: GoalSubtaskReviewState) : GoalSubtaskReviewPassReservation
internal data class GoalSubtaskReviewPassCarryForward(
  val state: GoalSubtaskReviewState,
) : GoalSubtaskReviewPassReservation

internal sealed interface GoalSubtaskReviewInputPreparation {
  data object MissingState : GoalSubtaskReviewInputPreparation
}

internal data class GoalSubtaskReviewInputBlocked(val reason: String) : GoalSubtaskReviewInputPreparation
internal data class GoalSubtaskReviewInputReady(
  val state: GoalSubtaskReviewState,
  val input: GoalSubtaskReviewInput,
) : GoalSubtaskReviewInputPreparation
