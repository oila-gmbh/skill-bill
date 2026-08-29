package skillbill.application.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalrunner.GoalLifecycleTelemetryEmitter
import skillbill.application.goalrunner.GoalRunnerChildRepairStore
import skillbill.application.goalrunner.GoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import java.time.Clock

@Inject
data class GoalRunnerDeps(
  val manifestStore: GoalRunnerManifestStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val goalPlanningSweep: GoalPlanningSweep,
  val specScratchStore: SpecScratchStore,
  val gitOperations: WorkflowGitOperations,
  val telemetry: GoalLifecycleTelemetryEmitter,
  val clock: Clock,
  val diagnostics: RuntimeDiagnostics,
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  val executionCoordinator: GoalRunnerExecutionCoordinator,
)

@Inject
data class GoalRunnerStatusServiceDeps(
  val manifestStore: GoalRunnerManifestStore,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  val gitOperations: WorkflowGitOperations,
  val attemptLedgerStore: GoalRunnerAttemptLedgerStore,
  val clock: Clock,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val childRepairStore: GoalRunnerChildRepairStore,
  val planningStatusReasonCoherence: GoalPlanningStatusReasonCoherence,
  val diagnostics: RuntimeDiagnostics,
  val runtimeStatusService: FeatureTaskRuntimeStatusService?,
)

@Inject
data class WorkflowGoalRunnerManifestStoreDeps(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val clock: Clock,
)

@Inject
data class WorkflowGoalRunnerOutcomeStoreDeps(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  val goalProgressEventValidator: GoalProgressEventValidator,
  val gitOperations: WorkflowGitOperations,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
)
