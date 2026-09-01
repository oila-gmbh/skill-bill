package skillbill.infrastructure.sqlite.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.persistence.model.WorkflowGoalRunnerOutcomeStoreDeps
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore

class WorkflowGoalRunnerOutcomeStore private constructor(
  workflow: GoalRunnerWorkflowOutcomeStore,
  ledger: GoalRunnerAttemptLedgerStore,
  childRepair: GoalRunnerChildRepairStore,
) : GoalRunnerWorkflowOutcomeStore by workflow,
  GoalRunnerAttemptLedgerStore by ledger,
  GoalRunnerChildRepairStore by childRepair {
  @Inject
  constructor(deps: WorkflowGoalRunnerOutcomeStoreDeps) : this(
    createWorkflowGoalRunnerOutcomeStoreBridges(
      CreateWorkflowGoalRunnerOutcomeStoreBridgesArgs(
        database = deps.database,
        workflowSnapshotValidator = deps.workflowSnapshotValidator,
        goalObservabilityEventValidator = deps.goalObservabilityEventValidator,
        goalProgressEventValidator = deps.goalProgressEventValidator,
        gitOperations = deps.gitOperations,
        phaseOutputValidator = deps.phaseOutputValidator,
        workerSupervisor = deps.workerSupervisor,
        decompositionManifestValidator = deps.decompositionManifestValidator,
        decompositionManifestFileStore = deps.decompositionManifestFileStore,
        clock = deps.clock,
        decompositionManifestWriter = deps.decompositionManifestWriter,
        childRepairExecutor = deps.childRepairExecutor,
      ),
    ),
  )

  private constructor(bridges: WorkflowGoalRunnerOutcomeStoreBridges) : this(
    workflow = bridges.workflow,
    ledger = bridges.ledger,
    childRepair = bridges.childRepair,
  )
}
