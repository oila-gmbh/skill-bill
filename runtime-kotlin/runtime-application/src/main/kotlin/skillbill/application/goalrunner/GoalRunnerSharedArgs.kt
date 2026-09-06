package skillbill.application.goalrunner
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.GoalPlanningSharedContext
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

internal data class DriveGoalLoopArgs(
  val initialState: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val attempted: MutableList<Int>,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val telemetryEmitter: GoalRunnerTelemetryEmitter,
  val planning: GoalPlanningSweepOutcome.PreparedAll,
)

internal data class BlockedSelectionIterationArgs(
  val state: GoalRunnerManifestState,
  val selection: GoalRunnerSelection.Blocked,
  val request: GoalRunnerRunRequest,
  val attempted: List<Int>,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
)

internal data class SubtaskLaunchRequestArgs(
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: WorkflowId?,
  val reviewBaseline: GoalSubtaskReviewBaseline?,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class RunSelectedSubtaskArgs(
  val state: GoalRunnerManifestState,
  val selection: GoalRunnerSelection.Run,
  val request: GoalRunnerRunRequest,
  val attempted: MutableList<Int>,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val telemetryEmitter: GoalRunnerTelemetryEmitter?,
  val planning: GoalPlanningSweepOutcome.PreparedAll,
)

internal data class DispatchWorkerResultArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: SubtaskId,
  val reconciled: GoalRunnerReconciledOutcome,
  val workerRequestResult: GoalRunnerWorkerRequestHandlingResult,
  val launchReconciliation: GoalRunnerLaunchReconciliation,
  val request: GoalRunnerRunRequest,
  val attempted: MutableList<Int>,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val attemptStartMillis: Long?,
)

internal data class RecordPostLaunchStateArgs(
  val refreshed: GoalRunnerManifestState,
  val subtaskId: SubtaskId,
  val selection: GoalRunnerSelection.Run,
  val reconciliation: GoalRunnerLaunchReconciliation,
  val request: GoalRunnerRunRequest,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val reAttemptCause: String?,
  val causingLoopEntry: String?,
)

internal data class LaunchSubtaskWithWorkerResultArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: SubtaskId,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: WorkflowId?,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class LaunchAndReconcileSubtaskArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: SubtaskId,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: WorkflowId?,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class RecordStoppedLedgerEntriesArgs(
  val workflowId: WorkflowId,
  val state: GoalRunnerManifestState,
  val subtaskId: SubtaskId,
  val stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
  val reconciled: GoalRunnerReconciledOutcome.Stop,
  val launchDiagnostics: GoalRunnerLaunchDiagnostics?,
  val attemptDurationMillis: Long?,
  val ledger: GoalRunnerLedgerRecorder,
  val request: GoalRunnerRunRequest,
)

internal data class RecordCompletedSubtaskArgs(
  val completed: GoalRunnerManifestState,
  val subtaskId: SubtaskId,
  val reconciled: GoalRunnerReconciledOutcome.Complete,
  val request: GoalRunnerRunRequest,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val attemptStartMillis: Long?,
)

internal data class StoppedReportArgs(
  val issueKey: IssueKey,
  val attempted: List<Int>,
  val subtaskId: SubtaskId,
  val reason: GoalRunnerStopReason,
  val blockedReason: String,
  val workflowId: WorkflowId?,
  val lastResumableStep: String,
)

internal data class ProduceMissingPlansArgs(
  val shared: GoalPlanningSharedContext,
  val request: GoalRunnerRunRequest,
  val identity: GoalPlanningIdentity,
  val provenance: GoalPlanningContractProvenance,
  val sharedCheckpoint: SharedGoalPreplanCheckpoint,
  val activeSubtasks: List<DecompositionSubtask>,
)

internal data class ProducePlanArgs(
  val shared: GoalPlanningSharedContext,
  val request: GoalRunnerRunRequest,
  val subtask: DecompositionSubtask,
  val descriptor: GovernedGoalSubtaskDescriptor,
  val provenance: GoalPlanningContractProvenance,
  val preplanPayload: String,
  val resolvedBodies: GoalPlanningResolvedBoundaryBodies,
  val outputSink: AgentRunOutputSink,
)

internal data class EmptyOrStoppedArgs(
  val outcome: AgentRunLaunchOutcome,
  val shared: GoalPlanningSharedContext,
  val request: GoalRunnerRunRequest,
  val currentSubtaskId: Int,
  val phaseId: String,
  val durationMs: Long,
)

internal data class BuildDeclaredGoalProgressEventArgs(
  val sourceLabel: String,
  val eventKind: GoalProgressEventKind,
  val workflowId: WorkflowId,
  val workflowPhase: String,
  val sequenceNumber: Int,
  val timestamp: String,
  val outcome: GoalProgressOutcome,
)
