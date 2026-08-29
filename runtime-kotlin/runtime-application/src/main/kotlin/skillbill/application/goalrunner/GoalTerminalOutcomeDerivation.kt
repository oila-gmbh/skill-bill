package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState

internal fun terminalOutcomeFor(
  snapshot: WorkflowStateSnapshot,
  artifacts: Map<String, Any?>,
  goalContinuation: GoalContinuation,
  measuredCommitSha: () -> String? = { null },
): GoalRunnerStoredOutcome? {
  val stored = goalContinuationOutcome(
    artifacts = artifacts,
    issueKey = goalContinuation.issueKey,
    subtaskId = goalContinuation.subtaskId,
    suppressPr = goalContinuation.suppressPr,
  )
    ?.takeUnless { it.status == GoalRunnerTerminalStatus.COMPLETE && it.commitSha.isNullOrBlank() }
    ?.copy(workflowId = snapshot.workflowId)
  if (stored != null) {
    if (stored.status == GoalRunnerTerminalStatus.COMPLETE ||
      nonCompleteStoredOutcomeIsCorroborated(
        stored,
        derivedTerminalOutcomeFor(snapshot, artifacts, goalContinuation, measuredCommitSha),
        snapshot,
      )
    ) {
      return stored
    }
  }
  return derivedTerminalOutcomeFor(snapshot, artifacts, goalContinuation, measuredCommitSha)
}

internal fun derivedTerminalOutcomeFor(
  snapshot: WorkflowStateSnapshot,
  artifacts: Map<String, Any?>,
  goalContinuation: GoalContinuation,
  measuredCommitSha: () -> String?,
): GoalRunnerStoredOutcome? {
  val steps = decodeWorkflowSteps(snapshot.stepsJson)
  val commitSha = commitShaFrom(artifacts)
    ?: if (commitPushCompletedUnderSuppressPr(steps, goalContinuation.suppressPr)) measuredCommitSha() else null
  return terminalStatus(snapshot, steps, goalContinuation.suppressPr, commitSha)?.let { status ->
    GoalRunnerStoredOutcome(
      status = status,
      workflowId = snapshot.workflowId,
      commitSha = commitSha,
      blockedReason = blockedReasonFrom(artifacts, steps, status),
      lastResumableStep = snapshot.currentStepId,
      suppressPr = goalContinuation.suppressPr,
    )
  }
}

internal fun nonCompleteStoredOutcomeIsCorroborated(
  stored: GoalRunnerStoredOutcome,
  derived: GoalRunnerStoredOutcome?,
  snapshot: WorkflowStateSnapshot,
): Boolean = when (stored.status) {
  GoalRunnerTerminalStatus.BLOCKED ->
    derived?.status == GoalRunnerTerminalStatus.BLOCKED &&
      derived.blockedReason == stored.blockedReason
  GoalRunnerTerminalStatus.FAILED -> derived?.status == GoalRunnerTerminalStatus.FAILED
  GoalRunnerTerminalStatus.PAUSED -> snapshot.workflowStatus == "paused"
  GoalRunnerTerminalStatus.TIMEOUT -> true
  GoalRunnerTerminalStatus.COMPLETE,
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerTerminalStatus.RECONCILABLE,
  -> false
}

internal fun List<GoalContinuationCandidate>.authoritativeOutcomesBySubtask(): Map<Int, GoalRunnerStoredOutcome> =
  groupBy { candidate -> candidate.goalContinuation.subtaskId }
    .mapNotNull { (subtaskId, candidates) ->
      candidates.selectAuthoritativeOutcome()?.let { outcome -> subtaskId to outcome }
    }
    .toMap()

internal fun List<GoalContinuationCandidate>.selectAuthoritativeOutcome(): GoalRunnerStoredOutcome? {
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

internal fun staleRunningReason(
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

internal fun missingResultPrefixTerminalOutcomeArtifact(
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

internal fun Map<String, Any?>.matchesGoalContinuation(issueKey: String, subtaskId: Int): Boolean {
  val candidateIssueKey = this["issue_key"]?.toString()?.takeIf(String::isNotBlank) ?: issueKey
  val candidateSubtaskId = this["subtask_id"].asGoalRunnerIntOrNull() ?: subtaskId
  return candidateIssueKey == issueKey && candidateSubtaskId == subtaskId
}

internal fun Map<String, Any?>.toMissingResultPrefixOutcomeArtifact(
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

internal fun GoalRunnerTerminalStatus.toGoalContinuationWireStatus(): String = when (this) {
  GoalRunnerTerminalStatus.COMPLETE -> "complete"
  GoalRunnerTerminalStatus.FAILED -> "failed"
  GoalRunnerTerminalStatus.BLOCKED -> "blocked"
  GoalRunnerTerminalStatus.TIMEOUT -> "timeout"
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME -> "no_terminal_store_outcome"
  GoalRunnerTerminalStatus.RECONCILABLE -> "reconcilable"
  GoalRunnerTerminalStatus.PAUSED -> "paused"
}

internal fun goalContinuationTerminalStatus(status: String?): GoalRunnerTerminalStatus? = when (status) {
  "complete", "completed" -> GoalRunnerTerminalStatus.COMPLETE
  "failed" -> GoalRunnerTerminalStatus.FAILED
  "blocked" -> GoalRunnerTerminalStatus.BLOCKED
  "timeout", "timed_out" -> GoalRunnerTerminalStatus.TIMEOUT
  "paused" -> GoalRunnerTerminalStatus.PAUSED
  else -> null
}

internal fun terminalStatus(
  snapshot: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  suppressPr: Boolean,
  commitSha: String?,
): GoalRunnerTerminalStatus? = when {
  commitPushCompletedUnderSuppressPr(steps, suppressPr) ->
    if (commitSha.isNullOrBlank()) {
      GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
    } else {
      GoalRunnerTerminalStatus.COMPLETE
    }
  snapshot.workflowStatus == "failed" || steps.any { it.status == "failed" } -> GoalRunnerTerminalStatus.FAILED
  snapshot.workflowStatus == "blocked" || liveBlockedStep(snapshot, steps) != null -> GoalRunnerTerminalStatus.BLOCKED
  snapshot.workflowStatus in setOf("completed", "abandoned") -> GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
  else -> null
}

internal fun liveBlockedStep(snapshot: WorkflowStateSnapshot, steps: List<WorkflowStepState>): WorkflowStepState? {
  val currentIndex = steps.indexOfFirst { it.stepId == snapshot.currentStepId }
  if (currentIndex < 0) return steps.firstOrNull { it.status == "blocked" }
  return steps.drop(currentIndex).firstOrNull { it.status == "blocked" }
}

internal fun blockedReasonFrom(
  artifacts: Map<String, Any?>,
  steps: List<WorkflowStepState>,
  status: GoalRunnerTerminalStatus,
): String? = artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
  ?: (artifacts["goal_continuation_outcome"] as? Map<*, *>)
    ?.get("blocked_reason")?.toString()?.takeIf(String::isNotBlank)
  ?: steps.firstOrNull { it.status in setOf("failed", "blocked") }
    ?.let { step -> "Workflow step '${step.stepId}' is ${step.status}." }
  ?: "Workflow reached a terminal state without a goal-continuation commit SHA."
    .takeIf { status == GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME }

internal fun commitShaFrom(artifacts: Map<String, Any?>): String? =
  (artifacts["commit_push_result"] as? Map<*, *>)?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)

internal fun commitPushCompletedUnderSuppressPr(steps: List<WorkflowStepState>, suppressPr: Boolean): Boolean =
  suppressPr && steps.any { it.stepId == "commit_push" && it.status == "completed" }

internal fun maxHistorySequence(artifacts: Map<String, Any?>, historyKey: String, current: Int?): Int? {
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
