package skillbill.infrastructure.sqlite.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.DecompositionManifestValidator

class WorkflowGoalRunnerOutcomeStore private constructor(
  bridges: WorkflowGoalRunnerOutcomeStoreBridges,
) : GoalRunnerWorkflowOutcomeStore by bridges.workflow,
  GoalRunnerAttemptLedgerStore by bridges.ledger,
  GoalRunnerChildRepairStore by bridges.childRepair {
  @Inject
  constructor(
    bridgeBuilder: WorkflowGoalRunnerOutcomeStoreBridgeBuilder,
    decompositionManifestValidator: DecompositionManifestValidator,
    decompositionManifestStore: DecompositionManifestStore,
    decompositionManifestWriter: DecompositionManifestProjectionWriter,
    childRepairExecutor: GoalRunnerChildRepairRunnerPort,
  ) : this(
    bridgeBuilder.build(
      decompositionManifestValidator = decompositionManifestValidator,
      decompositionManifestStore = decompositionManifestStore,
      decompositionManifestWriter = decompositionManifestWriter,
      childRepairExecutor = childRepairExecutor,
    ),
  )
}
