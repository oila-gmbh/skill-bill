package skillbill.application.goalrunner

import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairDiagnoseRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.runner.model.GoalChildPlanningHydrationRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.workflow.engine.model.WorkflowStateSnapshot

object NoopGoalChildPlanningHydrator : GoalChildPlanningHydratorPort {
  override fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult = GoalChildPlanningHydrationResult(
    currentStepId = setup.workflowId,
    stepUpdates = emptyList(),
    artifacts = emptyMap(),
  )

  override fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) = Unit
}

object NoopGoalRunnerChildRepairRunner : GoalRunnerChildRepairRunnerPort {
  override fun diagnose(request: GoalRunnerChildRepairDiagnoseRequest): GoalRunnerChildWedgeDiagnosis =
    GoalRunnerChildWedgeDiagnosis(
      subtaskId = request.subtaskId,
      workflowId = request.workflowId,
      passedChecks = emptyList(),
    )

  override fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult =
    GoalRunnerChildRepairApplyResult()
}
