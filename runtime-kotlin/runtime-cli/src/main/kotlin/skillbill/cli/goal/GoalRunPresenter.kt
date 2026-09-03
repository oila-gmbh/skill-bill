package skillbill.cli.goal

import skillbill.application.goalrunner.model.GoalRunnerEventSink
import skillbill.application.goalrunner.model.GoalRunnerPauseResult
import skillbill.application.goalrunner.model.GoalRunnerResumeResult
import skillbill.application.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.system.RuntimeProvenanceContract
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.nio.file.Path

class GoalRunPresenter(
  private val issueKey: String,
  private val inputs: CliRunInputs,
  private val liveOutput: Boolean,
  private val repoRoot: Path,
  private val dbOverride: String?,
  private val runtimeProvenance: RuntimeProvenanceContract,
) {
  fun emitStartupProvenance() {
    val commandPrefix = buildString {
      append("skill-bill")
      dbOverride?.let { append(" --db ").append(shellQuote(it)) }
    }
    val rootArgument = "--repo-root ${shellQuote(repoRoot.toString())}"
    inputs.liveStdout(
      "goal $issueKey: launched runtime executable=${runtimeProvenance.executablePath} " +
        "version=${runtimeProvenance.version} build_id=${runtimeProvenance.buildId}\n" +
        "monitor (read-only; mutates nothing; no model tokens):\n" +
        "$commandPrefix goal watch $issueKey $rootArgument --interval-seconds 5\n" +
        "$commandPrefix goal status $issueKey $rootArgument --diff-stat\n",
    )
  }

  fun eventSink(): GoalRunnerEventSink = GoalRunnerEventSink { }

  fun outputSink(includeRawChildOutput: Boolean): AgentRunOutputSink = if (!liveOutput) {
    AgentRunOutputSink.NONE
  } else {
    AgentRunOutputSink { stream, text ->
      if (includeRawChildOutput) {
        when (stream) {
          AgentRunOutputStream.STDOUT -> inputs.liveStdout(text)
          AgentRunOutputStream.STDERR -> inputs.liveStderr(text)
        }
      }
    }
  }
}

internal fun GoalRunnerRunReport.toGoalRunCliMap(): Map<String, Any?> = when (this) {
  is GoalRunnerRunReport.Completed -> linkedMapOf(
    "status" to "complete",
    "issue_key" to issueKey,
    "feature_name" to featureName,
    "attempted_subtasks" to attemptedSubtasks,
    "subtasks_completed" to subtasksCompleted,
    "subtasks_pending" to subtasksPending,
    "subtasks_blocked" to subtasksBlocked,
    "unaddressed_findings" to unaddressedFindingCount,
    "unaddressed_severity_breakdown" to unaddressedSeverityBreakdown,
    "pull_request_status" to pullRequestStatus,
    "pull_request_url" to pullRequestUrl,
  )
  is GoalRunnerRunReport.Stopped -> linkedMapOf(
    "status" to "stopped",
    "issue_key" to issueKey,
    "attempted_subtasks" to attemptedSubtasks,
    "subtask_id" to stop.subtaskId,
    "reason" to stop.reason.name.lowercase(),
    "blocked_reason" to stop.blockedReason,
    "workflow_id" to stop.workflowId,
    "last_resumable_step" to stop.lastResumableStep,
  )
}

internal fun GoalRunnerPauseResult.toGoalPauseCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to status,
  "issue_key" to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "paused" to paused,
  "pause_requested" to pauseRequested,
  "pause_reason" to pauseReason,
)

internal fun goalPauseText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal ${payload["issue_key"]}: ${payload["status"]}")
  payload["pause_reason"]?.let { appendLine("reason: $it") }
}

internal fun GoalRunnerStopVerbResult.toGoalStopCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to status.wireValue,
  "issue_key" to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "pause_reason" to pauseReason,
  "paused_at" to pausedAt,
  "termination_attempted" to terminationAttempted,
)

internal fun goalStopText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal ${payload["issue_key"]}: ${payload["status"]}")
  payload["pause_reason"]?.let { appendLine("reason: $it") }
  payload["paused_at"]?.let { appendLine("paused at: $it") }
}

internal fun GoalRunnerResumeResult.toGoalResumeCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to status,
  "issue_key" to issueKey,
  "parent_workflow_id" to parentWorkflowId,
  "paused" to false,
  "pause_requested" to false,
  "cleared_pause_reason" to clearedPauseReason,
)

internal fun goalResumeText(payload: Map<String, Any?>): String = buildString {
  appendLine("goal ${payload["issue_key"]}: ${payload["status"]}")
  payload["cleared_pause_reason"]?.let { appendLine("cleared reason: $it") }
}

internal fun goalRunText(payload: Map<String, Any?>): String = when (payload["status"]) {
  "complete" -> buildString {
    appendLine("goal ${payload["issue_key"]}: finished")
    append("summary: ")
    append(singleLineBounded(payload["feature_name"]?.toString().orEmpty().ifBlank { "goal" }))
    append(" — ")
    val completedCount = (payload["subtasks_completed"] as? Number)?.toInt() ?: 0
    val pendingCount = (payload["subtasks_pending"] as? Number)?.toInt() ?: 0
    val blockedCount = (payload["subtasks_blocked"] as? Number)?.toInt() ?: 0
    val totalCount = completedCount + pendingCount + blockedCount
    append(completedCount)
    append("/")
    append(totalCount)
    append(" subtasks complete; pending=")
    append(pendingCount)
    append("; blocked=")
    append(blockedCount)
    payload["pull_request_url"]?.toString()?.takeIf(String::isNotBlank)?.let { url ->
      append("; PR ")
      append(singleLineBounded(url))
    }
    appendLine()
  }
  else -> buildString {
    val reason = payload["reason"]?.toString()?.lowercase().orEmpty()
    val verb = when {
      reason == "paused" -> "paused"
      reason.contains("failed") || reason.contains("timeout") -> "failed"
      else -> "blocked"
    }
    append("goal ${payload["issue_key"]}: $verb")
    payload["subtask_id"]?.let { append(" at subtask $it") }
    append(" — ")
    append(singleLineBounded(payload["blocked_reason"]?.toString() ?: reason.ifBlank { "terminal outcome" }))
    appendLine()
  }
}

internal fun singleLineBounded(value: String, limit: Int = MAX_TERMINAL_FIELD_CHARS): String =
  value.replace(Regex("\\s+"), " ").trim().take(limit)

internal const val MAX_TERMINAL_FIELD_CHARS = 240

internal const val GOAL_STATUS_DATABASE_UNAVAILABLE = "database_unavailable"

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
