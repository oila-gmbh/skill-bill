package skillbill.engine.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.FeatureTaskRuntimeStatusService
import skillbill.engine.goalrunner.GoalRunnerFinalization
import skillbill.engine.goalrunner.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.GoalRunnerPauseBoundary
import skillbill.engine.goalrunner.GoalRunnerProgressReader
import skillbill.engine.goalrunner.GoalRunnerRunPreparation
import skillbill.engine.goalrunner.GoalRunnerSubtaskLaunchPrepare
import skillbill.engine.goalrunner.GoalRunnerWorkerRequestHandler
import skillbill.engine.goalplanning.GoalPlanningStatusReasonCoherence
import skillbill.model.RepositoryRoot
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.time.Clock

@Inject
data class GoalRunnerStatusProjectionAssemblerDeps(
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
  val repositoryRoot: RepositoryRoot,
)

@Inject
data class GoalRunnerDeps(
  val runBoundaries: GoalRunnerRunBoundariesPort,
  val launchBoundaries: GoalRunnerSubtaskLaunchBoundariesPort,
  val workerRequestHandler: GoalRunnerWorkerRequestHandler,
  val reconciler: GoalRunnerLaunchReconciler,
  val progressReader: GoalRunnerProgressReader,
  val pauseBoundary: GoalRunnerPauseBoundary,
  val runPreparation: GoalRunnerRunPreparation,
  val launchPrepare: GoalRunnerSubtaskLaunchPrepare,
  val finalization: GoalRunnerFinalization,
)
