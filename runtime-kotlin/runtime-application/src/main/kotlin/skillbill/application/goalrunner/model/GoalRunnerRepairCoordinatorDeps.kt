package skillbill.application.goalrunner.model

import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.GoalRunnerChildRepairStore
import skillbill.model.RepositoryRoot
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.gitops.WorkflowGitOperations

data class GoalRunnerRepairCoordinatorDeps(
  val manifestStore: GoalRunnerManifestStore,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val childRepairStore: GoalRunnerChildRepairStore,
  val gitOperations: WorkflowGitOperations,
  val portableReviewBaselinePersistence: PortableReviewBaselinePersistence,
  val repositoryRoot: RepositoryRoot,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
)
