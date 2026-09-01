package skillbill.application.goalrunner

import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyRequest
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairApplyResult
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.runner.model.GoalChildPlanningHydrationRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path

object NoopGoalChildPlanningHydrator : GoalChildPlanningHydratorPort {
  override fun hydrate(
    unitOfWork: UnitOfWork,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult = GoalChildPlanningHydrationResult(
    currentStepId = setup.workflowId,
    stepUpdates = emptyList(),
    artifacts = emptyMap(),
  )

  override fun requireMatchingImport(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) = Unit
}

object NoopGoalRunnerChildRepairRunner : GoalRunnerChildRepairRunnerPort {
  override fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis = GoalRunnerChildWedgeDiagnosis(
    subtaskId = subtaskId,
    workflowId = workflowId,
    passedChecks = emptyList(),
  )

  override fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult =
    GoalRunnerChildRepairApplyResult()
}
