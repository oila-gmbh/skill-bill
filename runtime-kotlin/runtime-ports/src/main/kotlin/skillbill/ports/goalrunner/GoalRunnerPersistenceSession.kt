package skillbill.ports.goalrunner

import skillbill.ports.review.ReviewRepository
import skillbill.ports.workflow.WorkflowStateRepository

interface GoalRunnerPersistenceSession {
  val workflowStates: WorkflowStateRepository
  val goalRunnerControls: GoalRunnerControlRepository
  val goalPlanningPreparations: GoalPlanningPreparationRepository
  val reviews: ReviewRepository
}
