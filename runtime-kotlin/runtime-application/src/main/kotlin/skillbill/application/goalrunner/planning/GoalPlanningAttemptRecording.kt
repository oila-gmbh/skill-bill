package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.planning.model.GoalPlanningAttemptRecord
import skillbill.application.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.application.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

internal fun DefaultGoalPlanningSweep.recordEmptyProviderTurn(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
  production: GoalPlanningPhaseProduction.EmptyProviderTurn,
) = recordPlanningRejection(
  shared,
  phaseId,
  subtask,
  attempt,
  GoalPlanningSweepConstants.EMPTY_PLANNING_HARVEST_RULE,
  production.reason,
  production.evidence.agentId,
  production.evidence.rawOutputPreview.orEmpty(),
)

internal fun DefaultGoalPlanningSweep.recordPlanningRejection(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
  rule: String,
  reason: String,
  agentId: String,
  rawEvidence: String,
) {
  planningRejectionRecorder.record(
    GoalPlanningRejectionRecord(
      parentWorkflowId = shared.parentWorkflowId,
      issueKey = shared.issueKey,
      dbPathOverride = shared.dbPathOverride,
      phaseId = diagnosticPhaseId(phaseId, subtask),
      subtaskId = subtask?.id ?: 0,
      attempt = attempt,
      rule = rule,
      reason = reason,
      agentId = agentId,
      rawEvidence = rawEvidence,
    ),
  )
}

internal fun diagnosticPhaseId(phaseId: String, subtask: DecompositionSubtask?): String =
  subtask?.let { "$phaseId:${it.id}" } ?: phaseId

internal fun DefaultGoalPlanningSweep.declineRetryStop(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
  declines: Int,
  production: GoalPlanningPhaseProduction.RetryableDecline,
): GoalPlanningPhaseProduction.Stopped? {
  recordFailedAttempt(shared, phaseId, subtask, attempt, GoalPlanningSweepConstants.RETRYABLE_PLANNING_DECLINE_RULE, production)
  if (declines >= GoalPlanningSweepConstants.MAX_RETRYABLE_PLANNING_DECLINES) {
    return GoalPlanningPhaseProduction.Stopped(
      stopped(shared, subtask?.id ?: 0, exhaustedDeclineReason(production, declines), phaseId),
    )
  }
  return backoffStop(shared, phaseId, subtask, attempt)
}

internal fun DefaultGoalPlanningSweep.backoffStop(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
): GoalPlanningPhaseProduction.Stopped? =
  interruptibleWait(burstSchedule.emptyTurnBackoffAfterAttempt(attempt), shared, subtask?.id ?: 0, phaseId)
    ?.let { stoppedOutcome -> GoalPlanningPhaseProduction.Stopped(stoppedOutcome) }

internal fun DefaultGoalPlanningSweep.recordFailedAttempt(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
  rule: String,
  production: GoalPlanningPhaseProduction,
) {
  recordPlanningAttempt(shared, phaseId, subtask, attempt, GoalProgressOutcome.FAILED)
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
  recordPlanningRejection(shared, phaseId, subtask, attempt, rule, reason, agentId, output)
}

internal fun DefaultGoalPlanningSweep.recordPlanningAttempt(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
  outcome: GoalProgressOutcome,
  eventKind: GoalProgressEventKind = GoalProgressEventKind.OPERATION_COMPLETED,
) {
  planningAttemptRecorder.record(
    GoalPlanningAttemptRecord(
      shared.parentWorkflowId,
      shared.issueKey,
      shared.dbPathOverride,
      phaseId,
      subtask?.id ?: 0,
      attempt,
      outcome,
      eventKind,
    ),
  )
}

internal fun DefaultGoalPlanningSweep.recordPlanningAttemptStarted(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
) = recordPlanningAttempt(
  shared,
  phaseId,
  subtask,
  attempt,
  GoalProgressOutcome.NONE,
  GoalProgressEventKind.OPERATION_STARTED,
)
