package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalContinuationCandidate
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus

fun List<GoalContinuationCandidate>.authoritativeOutcomesBySubtask(): Map<Int, GoalRunnerStoredOutcome> =
  groupBy { candidate -> candidate.goalContinuation.subtaskId }
    .mapNotNull { (subtaskId, candidates) ->
      candidates.selectAuthoritativeOutcome()?.let { outcome -> subtaskId to outcome }
    }
    .toMap()

fun List<GoalContinuationCandidate>.selectAuthoritativeOutcome(): GoalRunnerStoredOutcome? {
  val completeWinner = asSequence()
    .filter { candidate -> candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE }
    .maxWithOrNull(compareBy<GoalContinuationCandidate> { it.snapshot.updatedAt }.thenBy { it.snapshot.workflowId })
  if (completeWinner != null) {
    return completeWinner.outcome
  }
  val fallbackWinner = asSequence()
    .filter { candidate -> candidate.outcome != null }
    .maxWithOrNull(compareBy<GoalContinuationCandidate> { it.snapshot.updatedAt }.thenBy { it.snapshot.workflowId })
  return fallbackWinner?.outcome
}

fun staleRunningReason(
  staleWorkflowId: String,
  issueKey: String,
  subtaskId: Int,
  authoritative: GoalRunnerStoredOutcome?,
): String = authoritative?.let { outcome ->
  if (outcome.workflowId == staleWorkflowId) {
    "Goal status reconciliation closed inactive running child '$staleWorkflowId' for issue '$issueKey' " +
      "subtask $subtaskId because a terminal outcome was already durable."
  } else {
    "Goal status reconciliation closed stale running child '$staleWorkflowId' for issue '$issueKey' " +
      "subtask $subtaskId in favor of authoritative ${outcome.status.name.lowercase()} workflow " +
      "'${outcome.workflowId}'."
  }
} ?: (
  "Goal status reconciliation closed stale running child '$staleWorkflowId' for issue '$issueKey' " +
    "subtask $subtaskId because it was no longer active."
  )

@OpenBoundaryMap("Missing result-prefix terminal outcome artifact reconstruction")
fun missingResultPrefixTerminalOutcomeArtifact(
  output: Map<String, Any?>,
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
): Map<String, Any?>? = (JsonSupport.anyToStringAnyMap(output["subtask_outcome"]) ?: output)
  .takeIf { candidate -> candidate.matchesGoalContinuation(issueKey, subtaskId) }
  ?.let { candidate ->
    candidate["status"]?.toString()?.let(::goalContinuationTerminalStatus)?.let { status ->
      candidate.toMissingResultPrefixOutcomeArtifact(issueKey, subtaskId, workflowId, status)
    }
  }

fun Map<String, Any?>.matchesGoalContinuation(issueKey: String, subtaskId: Int): Boolean {
  val candidateIssueKey = this["issue_key"]?.toString()?.takeIf(String::isNotBlank) ?: issueKey
  val candidateSubtaskId = this["subtask_id"].asGoalRunnerIntOrNull() ?: subtaskId
  return candidateIssueKey == issueKey && candidateSubtaskId == subtaskId
}

fun Map<String, Any?>.toMissingResultPrefixOutcomeArtifact(
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
  status: GoalRunnerTerminalStatus,
): Map<String, Any?> = linkedMapOf<String, Any?>(
  "issue_key" to issueKey,
  "subtask_id" to subtaskId,
  "status" to status.toGoalContinuationWireStatus(),
  "workflow_id" to (this["workflow_id"]?.toString()?.takeIf(String::isNotBlank) ?: workflowId),
  "last_resumable_step" to (
    this["last_resumable_step"]?.toString()?.takeIf(String::isNotBlank) ?: "preplan"
    ),
).apply {
  this@toMissingResultPrefixOutcomeArtifact["commit_sha"]?.toString()?.takeIf(String::isNotBlank)
    ?.let { put("commit_sha", it) }
  this@toMissingResultPrefixOutcomeArtifact["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
    ?.let { put("blocked_reason", it) }
}

fun GoalRunnerTerminalStatus.toGoalContinuationWireStatus(): String = when (this) {
  GoalRunnerTerminalStatus.COMPLETE -> "complete"
  GoalRunnerTerminalStatus.FAILED -> "failed"
  GoalRunnerTerminalStatus.BLOCKED -> "blocked"
  GoalRunnerTerminalStatus.TIMEOUT -> "timeout"
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME -> "no_terminal_store_outcome"
  GoalRunnerTerminalStatus.RECONCILABLE -> "reconcilable"
  GoalRunnerTerminalStatus.PAUSED -> "paused"
}

fun goalContinuationTerminalStatus(status: String?): GoalRunnerTerminalStatus? = when (status) {
  "complete", "completed" -> GoalRunnerTerminalStatus.COMPLETE
  "failed" -> GoalRunnerTerminalStatus.FAILED
  "blocked" -> GoalRunnerTerminalStatus.BLOCKED
  "timeout", "timed_out" -> GoalRunnerTerminalStatus.TIMEOUT
  "paused" -> GoalRunnerTerminalStatus.PAUSED
  else -> null
}

@OpenBoundaryMap("Bounded history sequence scan over durable workflow artifacts")
fun maxHistorySequence(artifacts: Map<String, Any?>, historyKey: String, current: Int?): Int? {
  val entries = (artifacts[historyKey] as? List<*>).orEmpty()
  var max = current
  entries.forEach { item ->
    val sequence = (item as? Map<*, *>)?.get("sequence_number").asGoalRunnerIntOrNull()
    val currentMax = max
    if (sequence != null && (currentMax == null || sequence > currentMax)) {
      max = sequence
    }
  }
  return max
}
