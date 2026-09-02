package skillbill.application.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.model.GoalChildPlanningHydrationResult
import skillbill.ports.goalrunner.runner.model.GoalChildPlanningHydrationRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import java.time.Clock

@Inject
class GoalChildPlanningHydratorPortAdapter(
  phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  clock: Clock,
) : GoalChildPlanningHydratorPort {
  private val hydrator = GoalChildPlanningHydrator(phaseOutputValidator, planningProjectionValidator, clock)

  override fun hydrate(
    unitOfWork: UnitOfWork,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationResult = hydrator.hydrate(unitOfWork, setup, request).toPortResult()

  override fun requireMatchingImport(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) = hydrator.requireMatchingImport(unitOfWork, existing, setup)

  private fun GoalChildPlanningHydration.toPortResult() = GoalChildPlanningHydrationResult(
    currentStepId = currentStepId,
    stepUpdates = stepUpdates,
    artifacts = artifacts,
  )
}
