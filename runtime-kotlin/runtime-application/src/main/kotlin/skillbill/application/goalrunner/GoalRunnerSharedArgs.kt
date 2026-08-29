package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.GoalPlanningSharedContext
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.application.workflow.WorkflowFamily
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import java.time.Clock

@Inject
internal data class GoalRunnerStatusProjectionAssemblerDeps(
  val manifestStore: GoalRunnerManifestStore,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  val gitOperations: WorkflowGitOperations,
  val attemptLedgerStore: GoalRunnerAttemptLedgerStore,
  val clock: Clock,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val planningStatusReasonCoherence: GoalPlanningStatusReasonCoherence,
  val diagnostics: RuntimeDiagnostics,
  val runtimeStatusService: FeatureTaskRuntimeStatusService?,
)

@Inject
internal data class WorkflowGoalRunnerManifestStoreContextDeps(
  val database: DatabaseSessionFactory,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val clock: Clock,
)

@Inject
internal data class GoalRunnerFinalizationDeps(
  val manifestStore: GoalRunnerManifestStore,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val specScratchStore: SpecScratchStore,
  val gitOperations: WorkflowGitOperations,
  val diagnostics: RuntimeDiagnostics,
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  val progressReader: GoalRunnerProgressReader,
)

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
  val issueKey: String,
  val subtaskId: Int,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: String?,
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
  val subtaskId: Int,
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
  val subtaskId: Int,
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
  val subtaskId: Int,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: String?,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class LaunchAndReconcileSubtaskArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: String?,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class RecordStoppedLedgerEntriesArgs(
  val workflowId: String,
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
  val reconciled: GoalRunnerReconciledOutcome.Stop,
  val launchDiagnostics: GoalRunnerLaunchDiagnostics?,
  val attemptDurationMillis: Long?,
  val ledger: GoalRunnerLedgerRecorder,
  val request: GoalRunnerRunRequest,
)

internal data class RecordCompletedSubtaskArgs(
  val completed: GoalRunnerManifestState,
  val subtaskId: Int,
  val reconciled: GoalRunnerReconciledOutcome.Complete,
  val request: GoalRunnerRunRequest,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val attemptStartMillis: Long?,
)

internal data class StoppedReportArgs(
  val issueKey: String,
  val attempted: List<Int>,
  val subtaskId: Int,
  val reason: GoalRunnerStopReason,
  val blockedReason: String,
  val workflowId: String?,
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
  val workflowId: String,
  val workflowPhase: String,
  val sequenceNumber: Int,
  val timestamp: String,
  val outcome: GoalProgressOutcome,
)

internal data class RecoverMissingResultPrefixTerminalOutcomeArgs(
  val workflowStates: WorkflowStateRepository,
  val family: WorkflowFamily,
  val record: WorkflowStateSnapshot,
  val output: Map<String, Any?>,
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
)

internal data class CreateWorkflowGoalRunnerOutcomeStoreBridgesArgs(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  val goalProgressEventValidator: GoalProgressEventValidator,
  val gitOperations: WorkflowGitOperations,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val decompositionManifestValidator: DecompositionManifestValidator?,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
)

internal data class WorkflowGoalRunnerOutcomeStoreBridgesArgs(
  val database: DatabaseSessionFactory,
  val engine: WorkflowEngine,
  val gitOperations: WorkflowGitOperations,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val decompositionManifestValidator: DecompositionManifestValidator?,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val outcomeReconcile: WorkflowGoalRunnerOutcomeReconcile,
  val blockWrites: WorkflowGoalRunnerBlockWrites,
  val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  val progressRecording: WorkflowGoalRunnerProgressRecording,
  val childRepair: GoalRunnerChildRepairOperations,
)

internal data class WorkflowGoalRunnerManifestStoreBuildPartsArgs(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val clock: Clock,
)
