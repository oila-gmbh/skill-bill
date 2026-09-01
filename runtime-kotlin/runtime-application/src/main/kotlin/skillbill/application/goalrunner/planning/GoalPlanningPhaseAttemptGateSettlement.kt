package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.workflow.goal.model.GoalProgressOutcome

internal data class PlanningProduceAdvanceArgs(
  val attemptArgs: GoalPlanningProduceAttemptArgs,
  val scope: GoalPlanningAttemptScope,
  val retryableDeclines: Int,
  val phaseId: String,
  val finalizePayload: (String) -> String,
)

internal sealed interface PlanningProduceStep {
  class Done(val production: GoalPlanningPhaseProduction) : PlanningProduceStep
  class RetryDecline(val retryableDeclines: Int) : PlanningProduceStep
  class RetrySchema(val priorSchemaFailure: String) : PlanningProduceStep
}

internal sealed interface GoalPlanningPhaseProductionSettlement {
  data class Settled(val production: GoalPlanningPhaseProduction) : GoalPlanningPhaseProductionSettlement
  data class Retry(val priorSchemaFailure: String) : GoalPlanningPhaseProductionSettlement
  data class PendingCapture(val production: GoalPlanningPhaseProduction.Captured) :
    GoalPlanningPhaseProductionSettlement
}

internal fun DefaultGoalPlanningSweep.advancePlanningProduceAttempt(
  args: PlanningProduceAdvanceArgs,
): PlanningProduceStep {
  val production = produceAttemptOrStop(args.attemptArgs)
  return when (production) {
    is GoalPlanningPhaseProduction.RetryableDecline -> {
      val nextDeclines = args.retryableDeclines + 1
      declineRetryStop(this, args.scope, nextDeclines, production)
        ?.let { PlanningProduceStep.Done(it) }
        ?: PlanningProduceStep.RetryDecline(nextDeclines)
    }
    is GoalPlanningPhaseProduction.EmptyProviderTurn -> {
      recordPlanningAttempt(this, GoalPlanningAttemptRecordArgs(args.scope, GoalProgressOutcome.FAILED))
      recordEmptyProviderTurn(this, args.scope, production)
      backoffStop(this, args.scope)
        ?.let { PlanningProduceStep.Done(it) }
        ?: PlanningProduceStep.RetryDecline(args.retryableDeclines)
    }
    else -> when (val settlement = settlePlanningProductionAttempt(args.scope, production)) {
      is GoalPlanningPhaseProductionSettlement.Settled ->
        PlanningProduceStep.Done(settlement.production)
      is GoalPlanningPhaseProductionSettlement.Retry ->
        PlanningProduceStep.RetrySchema(settlement.priorSchemaFailure)
      is GoalPlanningPhaseProductionSettlement.PendingCapture ->
        when (
          val captured = settleCapturedPlanningProduction(
            args.scope,
            settlement.production,
            args.phaseId,
            args.finalizePayload,
          )
        ) {
          is GoalPlanningPhaseProductionSettlement.Settled ->
            PlanningProduceStep.Done(captured.production)
          is GoalPlanningPhaseProductionSettlement.Retry ->
            PlanningProduceStep.RetrySchema(captured.priorSchemaFailure)
          is GoalPlanningPhaseProductionSettlement.PendingCapture ->
            error("Unexpected nested capture settlement.")
        }
    }
  }
}

internal fun DefaultGoalPlanningSweep.settlePlanningProductionAttempt(
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction,
): GoalPlanningPhaseProductionSettlement = when (production) {
  is GoalPlanningPhaseProduction.Stopped -> {
    recordPlanningAttempt(this, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
    GoalPlanningPhaseProductionSettlement.Settled(production)
  }
  is GoalPlanningPhaseProduction.SchemaRejected -> {
    recordFailedAttempt(
      this,
      scope,
      GoalPlanningSweepConstants.SCHEMA_REJECTED_PLANNING_RULE,
      production,
    )
    GoalPlanningPhaseProductionSettlement.Retry(production.reason)
  }
  is GoalPlanningPhaseProduction.UnsuccessfulStatus -> {
    recordFailedAttempt(
      this,
      scope,
      GoalPlanningSweepConstants.UNSUCCESSFUL_PLANNING_STATUS_RULE,
      production,
    )
    GoalPlanningPhaseProductionSettlement.Settled(
      GoalPlanningPhaseProduction.Stopped(production.outcome),
    )
  }
  is GoalPlanningPhaseProduction.Captured ->
    GoalPlanningPhaseProductionSettlement.PendingCapture(production)
  is GoalPlanningPhaseProduction.RetryableDecline,
  is GoalPlanningPhaseProduction.EmptyProviderTurn,
  -> error("Decline/empty production must be settled before settlePlanningProductionAttempt.")
}

internal fun DefaultGoalPlanningSweep.settleCapturedPlanningProduction(
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction.Captured,
  phaseId: String,
  finalizePayload: (String) -> String,
): GoalPlanningPhaseProductionSettlement {
  val gated = gateCapturedPayload(production, phaseId, finalizePayload)
  return if (gated is GoalPlanningPhaseProduction.Captured) {
    recordPlanningAttempt(this, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.SUCCEEDED))
    GoalPlanningPhaseProductionSettlement.Settled(gated)
  } else {
    val rejected = gated as GoalPlanningPhaseProduction.SchemaRejected
    recordFailedAttempt(
      this,
      scope,
      GoalPlanningSweepConstants.SCHEMA_REJECTED_PLANNING_RULE,
      rejected,
    )
    GoalPlanningPhaseProductionSettlement.Retry(rejected.reason)
  }
}
