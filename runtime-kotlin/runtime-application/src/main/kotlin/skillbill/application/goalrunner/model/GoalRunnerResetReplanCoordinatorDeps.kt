package skillbill.application.goalrunner.model

import skillbill.application.goalrunner.GoalRunnerStatusProjectionAssembler
import skillbill.model.RepositoryRoot
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations

data class GoalRunnerResetReplanCoordinatorDeps(
  val manifestStore: GoalRunnerManifestStore,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val gitOperations: WorkflowGitOperations,
  val diagnostics: RuntimeDiagnostics,
  val projectionAssembler: GoalRunnerStatusProjectionAssembler,
  val repositoryRoot: RepositoryRoot,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
)
