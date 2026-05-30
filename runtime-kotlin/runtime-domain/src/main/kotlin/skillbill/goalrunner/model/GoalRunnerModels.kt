package skillbill.goalrunner.model

import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

enum class GoalRunnerTerminalStatus {
  COMPLETE,
  FAILED,
  BLOCKED,
  TIMEOUT,
  NO_TERMINAL_STORE_OUTCOME,
}

enum class GoalRunnerStopReason {
  FAILED,
  BLOCKED,
  TIMEOUT,
  NO_TERMINAL_STORE_OUTCOME,
  PULL_REQUEST_FAILED,
  DEPENDENCIES_BLOCKED,
}

data class GoalRunnerLaunchFacts(
  val timedOut: Boolean = false,
  val spawnFailed: Boolean = false,
  val exitStatus: Int? = null,
)

data class GoalRunnerStoredOutcome(
  val status: GoalRunnerTerminalStatus,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String? = null,
  val suppressPr: Boolean,
)

sealed interface GoalRunnerReconciledOutcome {
  data class Complete(
    val workflowId: String,
    val commitSha: String,
    val lastResumableStep: String,
  ) : GoalRunnerReconciledOutcome

  data class Stop(
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val workflowId: String?,
    val commitSha: String?,
    val lastResumableStep: String,
  ) : GoalRunnerReconciledOutcome
}

data class GoalRunnerSubtaskDecision(
  val subtask: DecompositionSubtask,
  val action: GoalRunnerSubtaskAction,
)

enum class GoalRunnerSubtaskAction {
  START,
  RESUME,
}

sealed interface GoalRunnerSelection {
  data class Run(val decision: GoalRunnerSubtaskDecision) : GoalRunnerSelection
  data class Blocked(val subtask: DecompositionSubtask, val reason: String) : GoalRunnerSelection
  data object Done : GoalRunnerSelection
}

data class GoalRunnerStopReport(
  val issueKey: String,
  val subtaskId: Int,
  val reason: GoalRunnerStopReason,
  val blockedReason: String,
  val workflowId: String?,
  val lastResumableStep: String,
)

data class GoalRunnerCompletedReport(
  val issueKey: String,
  val pullRequestUrl: String?,
  val subtasksCompleted: Int,
)

sealed interface GoalRunnerRunReport {
  val issueKey: String
  val attemptedSubtasks: List<Int>

  data class Completed(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val pullRequestUrl: String?,
    val subtasksCompleted: Int,
  ) : GoalRunnerRunReport

  data class Stopped(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val stop: GoalRunnerStopReport,
  ) : GoalRunnerRunReport
}

data class GoalRunnerStatusProjection(
  val issueKey: String,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val currentSubtaskId: Int?,
  val currentStep: String?,
  val activeAgent: String?,
)

object GoalRunnerStatusProjector {
  fun project(manifest: DecompositionManifest, activeAgent: String? = null): GoalRunnerStatusProjection {
    val currentSubtask = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId }
    return GoalRunnerStatusProjection(
      issueKey = manifest.issueKey,
      completeCount = manifest.subtasks.count { it.status == "complete" || it.status == "skipped" },
      pendingCount = manifest.subtasks.count { it.status !in setOf("complete", "skipped", "blocked") },
      blockedCount = manifest.subtasks.count { it.status == "blocked" },
      currentSubtaskId = currentSubtask?.id,
      currentStep = currentSubtask?.lastResumableStep,
      activeAgent = activeAgent?.takeIf(String::isNotBlank),
    )
  }
}
