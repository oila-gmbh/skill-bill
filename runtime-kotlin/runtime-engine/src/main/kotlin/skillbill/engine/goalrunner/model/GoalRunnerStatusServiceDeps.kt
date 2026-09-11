package skillbill.engine.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.FeatureTaskRuntimeStatusService
import skillbill.engine.goalplanning.GoalPlanningStatusReasonCoherence
import skillbill.model.RepositoryRoot
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.time.Clock

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
  val repositoryRoot: RepositoryRoot,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
)
