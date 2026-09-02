package skillbill.ports.goalrunner.persistence

import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult
import skillbill.ports.goalrunner.runner.model.GoalChildPlanningHydrationRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.workflow.engine.model.WorkflowStateSnapshot

interface GoalChildPlanningHydratorPort {
  fun hydrate(
    unitOfWork: UnitOfWork,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult

  fun requireMatchingImport(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  )
}
