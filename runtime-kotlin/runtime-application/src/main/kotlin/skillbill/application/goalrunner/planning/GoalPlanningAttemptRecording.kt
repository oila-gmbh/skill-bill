package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.planning.model.GoalPlanningAttemptRecord
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

internal fun recordEmptyProviderTurn(
  sweep: DefaultGoalPlanningSweep,
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction.EmptyProviderTurn,
) = recordPlanningRejection(
  sweep,
  GoalPlanningRejectionRecordArgs(
    scope = scope,
    rule = GoalPlanningSweepConstants.EMPTY_PLANNING_HARVEST_RULE,
    reason = production.reason,
    agentId = production.evidence.agentId,
    rawEvidence = production.evidence.rawOutputPreview.orEmpty(),
  ),
)

internal fun recordPlanningRejection(sweep: DefaultGoalPlanningSweep, args: GoalPlanningRejectionRecordArgs) {
  val scope = args.scope
  sweep.planningRejectionRecorder.record(
    GoalPlanningRejectionRecord(
      parentWorkflowId = scope.shared.parentWorkflowId,
      issueKey = scope.shared.issueKey,
      dbPathOverride = scope.shared.dbPathOverride,
      phaseId = diagnosticPhaseId(scope.phaseId, scope.subtask),
      subtaskId = scope.subtask?.id ?: 0,
      attempt = scope.attempt,
      rule = args.rule,
      reason = args.reason,
      agentId = args.agentId,
      rawEvidence = args.rawEvidence,
    ),
  )
}

fun diagnosticPhaseId(phaseId: String, subtask: DecompositionSubtask?): String =
  subtask?.let { "$phaseId:${it.id}" } ?: phaseId

internal fun declineRetryStop(
  sweep: DefaultGoalPlanningSweep,
  scope: GoalPlanningAttemptScope,
  declines: Int,
  production: GoalPlanningPhaseProduction.RetryableDecline,
): GoalPlanningPhaseProduction.Stopped? {
  recordFailedAttempt(
    sweep,
    scope,
    GoalPlanningSweepConstants.RETRYABLE_PLANNING_DECLINE_RULE,
    production,
  )
  if (declines >= GoalPlanningSweepConstants.MAX_RETRYABLE_PLANNING_DECLINES) {
    return GoalPlanningPhaseProduction.Stopped(
      stopped(scope.shared, scope.subtask?.id ?: 0, exhaustedDeclineReason(production, declines), scope.phaseId),
    )
  }
  return backoffStop(sweep, scope)
}

internal fun backoffStop(
  sweep: DefaultGoalPlanningSweep,
  scope: GoalPlanningAttemptScope,
): GoalPlanningPhaseProduction.Stopped? = sweep.interruptibleWait(
  sweep.burstSchedule.emptyTurnBackoffAfterAttempt(scope.attempt),
  scope.shared,
  scope.subtask?.id ?: 0,
  scope.phaseId,
)?.let { stoppedOutcome -> GoalPlanningPhaseProduction.Stopped(stoppedOutcome) }

internal fun recordFailedAttempt(
  sweep: DefaultGoalPlanningSweep,
  scope: GoalPlanningAttemptScope,
  rule: String,
  production: GoalPlanningPhaseProduction,
) {
  recordPlanningAttempt(sweep, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
  val (reason, output, agentId) = when (production) {
    is GoalPlanningPhaseProduction.SchemaRejected ->
      Triple(production.reason, production.rejectedOutput, production.agentId)

    is GoalPlanningPhaseProduction.UnsuccessfulStatus ->
      Triple(production.reason, production.rejectedOutput, production.agentId)

    is GoalPlanningPhaseProduction.RetryableDecline ->
      Triple(production.reason, production.rejectedOutput, production.agentId)

    is GoalPlanningPhaseProduction.Captured,
    is GoalPlanningPhaseProduction.EmptyProviderTurn,
    is GoalPlanningPhaseProduction.Stopped,
    -> return
  }
  recordPlanningRejection(
    sweep,
    GoalPlanningRejectionRecordArgs(
      scope = scope,
      rule = rule,
      reason = reason,
      agentId = agentId,
      rawEvidence = output,
    ),
  )
}

internal fun recordPlanningAttempt(sweep: DefaultGoalPlanningSweep, args: GoalPlanningAttemptRecordArgs) {
  val scope = args.scope
  sweep.planningAttemptRecorder.record(
    GoalPlanningAttemptRecord(
      scope.shared.parentWorkflowId,
      scope.shared.issueKey,
      scope.shared.dbPathOverride,
      scope.phaseId,
      scope.subtask?.id ?: 0,
      scope.attempt,
      args.outcome,
      args.eventKind,
    ),
  )
}

internal fun recordPlanningAttemptStarted(sweep: DefaultGoalPlanningSweep, scope: GoalPlanningAttemptScope) =
  recordPlanningAttempt(
    sweep,
    GoalPlanningAttemptRecordArgs(
      scope = scope,
      outcome = GoalProgressOutcome.NONE,
      eventKind = GoalProgressEventKind.OPERATION_STARTED,
    ),
  )

internal fun planningAttemptScope(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
) = GoalPlanningAttemptScope(shared, phaseId, subtask, attempt)
