package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.planning.model.GoalPlanningAttemptRecord
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

internal fun DefaultGoalPlanningSweep.recordEmptyProviderTurn(
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction.EmptyProviderTurn,
) = recordPlanningRejection(
  GoalPlanningRejectionRecordArgs(
    scope = scope,
    rule = GoalPlanningSweepConstants.EMPTY_PLANNING_HARVEST_RULE,
    reason = production.reason,
    agentId = production.evidence.agentId,
    rawEvidence = production.evidence.rawOutputPreview.orEmpty(),
  ),
)

internal fun DefaultGoalPlanningSweep.recordPlanningRejection(args: GoalPlanningRejectionRecordArgs) {
  val scope = args.scope
  planningRejectionRecorder.record(
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

internal fun diagnosticPhaseId(phaseId: String, subtask: DecompositionSubtask?): String =
  subtask?.let { "$phaseId:${it.id}" } ?: phaseId

internal fun DefaultGoalPlanningSweep.declineRetryStop(
  scope: GoalPlanningAttemptScope,
  declines: Int,
  production: GoalPlanningPhaseProduction.RetryableDecline,
): GoalPlanningPhaseProduction.Stopped? {
  recordFailedAttempt(
    scope,
    GoalPlanningSweepConstants.RETRYABLE_PLANNING_DECLINE_RULE,
    production,
  )
  if (declines >= GoalPlanningSweepConstants.MAX_RETRYABLE_PLANNING_DECLINES) {
    return GoalPlanningPhaseProduction.Stopped(
      stopped(scope.shared, scope.subtask?.id ?: 0, exhaustedDeclineReason(production, declines), scope.phaseId),
    )
  }
  return backoffStop(scope)
}

internal fun DefaultGoalPlanningSweep.backoffStop(
  scope: GoalPlanningAttemptScope,
): GoalPlanningPhaseProduction.Stopped? = interruptibleWait(
  burstSchedule.emptyTurnBackoffAfterAttempt(scope.attempt),
  scope.shared,
  scope.subtask?.id ?: 0,
  scope.phaseId,
)?.let { stoppedOutcome -> GoalPlanningPhaseProduction.Stopped(stoppedOutcome) }

internal fun DefaultGoalPlanningSweep.recordFailedAttempt(
  scope: GoalPlanningAttemptScope,
  rule: String,
  production: GoalPlanningPhaseProduction,
) {
  recordPlanningAttempt(GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
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
    GoalPlanningRejectionRecordArgs(
      scope = scope,
      rule = rule,
      reason = reason,
      agentId = agentId,
      rawEvidence = output,
    ),
  )
}

internal fun DefaultGoalPlanningSweep.recordPlanningAttempt(args: GoalPlanningAttemptRecordArgs) {
  val scope = args.scope
  planningAttemptRecorder.record(
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

internal fun DefaultGoalPlanningSweep.recordPlanningAttemptStarted(scope: GoalPlanningAttemptScope) =
  recordPlanningAttempt(
    GoalPlanningAttemptRecordArgs(
      scope = scope,
      outcome = GoalProgressOutcome.NONE,
      eventKind = GoalProgressEventKind.OPERATION_STARTED,
    ),
  )

internal fun DefaultGoalPlanningSweep.planningAttemptScope(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
) = GoalPlanningAttemptScope(shared, phaseId, subtask, attempt)
