package skillbill.ports.goalrunner.persistence

import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult
import skillbill.ports.goalrunner.runner.model.GoalChildPlanningHydrationRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.workflow.engine.model.WorkflowStateSnapshot

interface GoalChildPlanningHydratorPort {
  fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult

  fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  )
}
