package skillbill.application.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalrunner.GoalRunnerFinalization
import skillbill.application.goalrunner.GoalRunnerLaunchReconciler
import skillbill.application.goalrunner.GoalRunnerPauseBoundary
import skillbill.application.goalrunner.GoalRunnerProgressReader
import skillbill.application.goalrunner.GoalRunnerRunPreparation
import skillbill.application.goalrunner.GoalRunnerSubtaskLaunchPrepare
import skillbill.application.goalrunner.GoalRunnerWorkerRequestHandler
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
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
