package skillbill.goalrunner

import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.engine.decodeWorkflowSteps
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus

@OpenBoundaryMap("Terminal goal outcome derivation from durable workflow artifacts")
fun terminalOutcomeFor(
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

@OpenBoundaryMap("Derived terminal goal outcome from durable workflow artifacts")
fun derivedTerminalOutcomeFor(
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

fun nonCompleteStoredOutcomeIsCorroborated(
  stored: GoalRunnerStoredOutcome,
  derived: GoalRunnerStoredOutcome?,
  snapshot: WorkflowStateSnapshot,
): Boolean = when (stored.status) {
  GoalRunnerTerminalStatus.BLOCKED ->
    derived?.status == GoalRunnerTerminalStatus.BLOCKED &&
      derived.blockedReason == stored.blockedReason
  GoalRunnerTerminalStatus.FAILED -> derived?.status == GoalRunnerTerminalStatus.FAILED
  GoalRunnerTerminalStatus.PAUSED -> snapshot.workflowStatus.workflowStatus() == WorkflowStatus.PAUSED
  GoalRunnerTerminalStatus.TIMEOUT -> true
  GoalRunnerTerminalStatus.COMPLETE,
  GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerTerminalStatus.RECONCILABLE,
  -> false
}

fun terminalStatus(
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
  snapshot.workflowStatus.workflowStatus() == WorkflowStatus.FAILED ||
    steps.any { it.status.workflowStepStatus() == WorkflowStepStatus.FAILED } -> GoalRunnerTerminalStatus.FAILED
  snapshot.workflowStatus.workflowStatus() == WorkflowStatus.BLOCKED ||
    liveBlockedStep(snapshot, steps) != null -> GoalRunnerTerminalStatus.BLOCKED
  snapshot.workflowStatus.workflowStatus() in setOf(WorkflowStatus.COMPLETED, WorkflowStatus.ABANDONED) ->
    GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
  else -> null
}

fun liveBlockedStep(snapshot: WorkflowStateSnapshot, steps: List<WorkflowStepState>): WorkflowStepState? {
  val currentIndex = steps.indexOfFirst { it.stepId == snapshot.currentStepId }
  if (currentIndex < 0) return steps.firstOrNull {
    it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED
  }
  return steps.drop(currentIndex).firstOrNull {
    it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED
  }
}

@OpenBoundaryMap("Blocked reason extraction from durable workflow artifacts")
fun blockedReasonFrom(
  artifacts: Map<String, Any?>,
  steps: List<WorkflowStepState>,
  status: GoalRunnerTerminalStatus,
): String? = artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
  ?: (artifacts["goal_continuation_outcome"] as? Map<*, *>)
    ?.get("blocked_reason")?.toString()?.takeIf(String::isNotBlank)
  ?: steps.firstOrNull {
    it.status.workflowStepStatus() in setOf(WorkflowStepStatus.FAILED, WorkflowStepStatus.BLOCKED)
  }?.let { step -> "Workflow step '${step.stepId}' is ${step.status}." }
  ?: "Workflow reached a terminal state without a goal-continuation commit SHA."
    .takeIf { status == GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME }

@OpenBoundaryMap("Commit SHA extraction from durable workflow artifacts")
fun commitShaFrom(artifacts: Map<String, Any?>): String? =
  (artifacts["commit_push_result"] as? Map<*, *>)?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)

fun commitPushCompletedUnderSuppressPr(steps: List<WorkflowStepState>, suppressPr: Boolean): Boolean =
  suppressPr && steps.any {
    it.stepId == "commit_push" && it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED
  }
