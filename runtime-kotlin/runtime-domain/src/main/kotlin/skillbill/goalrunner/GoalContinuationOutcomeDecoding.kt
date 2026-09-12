package skillbill.goalrunner

import skillbill.contracts.SharedPayloadKeys

import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus

fun goalContinuationTerminalStatus(status: String?): GoalRunnerTerminalStatus? =
  status?.let(GoalRunnerTerminalStatus::fromWire)

@OpenBoundaryMap("Goal continuation outcome decode from durable workflow artifacts")
fun goalContinuationOutcome(
  artifacts: Map<String, Any?>,
  issueKey: String,
  subtaskId: Int,
  suppressPr: Boolean,
): GoalRunnerStoredOutcome? = (artifacts["goal_continuation_outcome"] as? Map<*, *>)
  ?.takeIf { outcome -> outcome[SharedPayloadKeys.ISSUE_KEY]?.toString() == issueKey }
  ?.takeIf { outcome -> outcome[SharedPayloadKeys.SUBTASK_ID].asGoalRunnerIntOrNull() == subtaskId }
  ?.let { outcome ->
    goalContinuationTerminalStatus(outcome[SharedPayloadKeys.STATUS]?.toString())?.let { status ->
      GoalRunnerStoredOutcome(
        status = status,
        workflowId = outcome[SharedPayloadKeys.WORKFLOW_ID]?.toString().orEmpty(),
        commitSha = outcome["commit_sha"]?.toString()?.takeIf(String::isNotBlank),
        blockedReason = outcome["blocked_reason"]?.toString()?.takeIf(String::isNotBlank),
        lastResumableStep = outcome["last_resumable_step"]?.toString()?.takeIf(String::isNotBlank),
        suppressPr = suppressPr,
      )
    }
  }
