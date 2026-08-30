package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.goalrunner.model.GoalRunnerWedgeFinding
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.engine.model.WorkflowStateSnapshot

internal data class GoalRunnerStaleBlockedOutcomeContext(
  val record: WorkflowStateSnapshot,
  val artifacts: Map<String, Any?>,
  val issueKey: String,
  val subtaskId: Int,
)

internal fun diagnoseStaleBlockedOutcome(
  context: GoalRunnerStaleBlockedOutcomeContext,
  wedges: MutableList<GoalRunnerWedgeFinding>,
  passed: MutableList<String>,
) {
  val identity = goalContinuation(context.artifacts)
    ?.takeIf { it.issueKey == context.issueKey && it.subtaskId == context.subtaskId }
  if (identity == null) {
    passed += PASSED_CONTINUATION_OUTCOME
    return
  }
  val stored = goalContinuationOutcome(context.artifacts, context.issueKey, context.subtaskId, identity.suppressPr)
    ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
  if (stored == null) {
    passed += PASSED_CONTINUATION_OUTCOME
    return
  }
  val derived = derivedTerminalOutcomeFor(context.record, context.artifacts, identity) { null }
  if (
    nonCompleteStoredOutcomeIsCorroborated(
      stored.copy(workflowId = context.record.workflowId),
      derived,
      context.record,
    )
  ) {
    passed += PASSED_CONTINUATION_OUTCOME
  } else {
    wedges += GoalRunnerWedgeFinding(
      wedgeClass = GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
      field = GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME.durableField,
      currentValue = stored.blockedReason,
    )
  }
}
