package skillbill.mcp.lifecycle

import skillbill.application.model.GoalFinishedRequest
import skillbill.application.model.GoalStartedRequest
import skillbill.application.model.GoalSubtaskFinishedRequest
import skillbill.mcp.core.McpRuntime
import skillbill.mcp.core.McpRuntimeContext
import skillbill.mcp.core.boolean
import skillbill.mcp.core.int
import skillbill.mcp.core.optionalString
import skillbill.mcp.core.string

internal fun goalProseStarted(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.goalProseStarted(
    GoalStartedRequest(
      issueKey = arguments.string("issue_key"),
      featureName = arguments.string("feature_name"),
      workflowId = arguments.string("workflow_id"),
      subtaskTotal = arguments.int("subtask_total", 1),
      resumed = arguments.boolean("resumed"),
      startedAt = arguments.string("started_at"),
      mode = "prose",
    ),
    context,
  )

internal fun goalProseSubtaskFinished(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.goalProseSubtaskFinished(
    GoalSubtaskFinishedRequest(
      issueKey = arguments.string("issue_key"),
      workflowId = arguments.string("workflow_id"),
      subtaskId = arguments.int("subtask_id", 1),
      subtaskName = arguments.string("subtask_name"),
      status = arguments.string("status"),
      startedAt = arguments.string("started_at"),
      finishedAt = arguments.string("finished_at"),
      durationMs = arguments.int("duration_ms", 0).toLong(),
      attemptCount = arguments.int("attempt_count", 1),
      blockedReason = arguments.optionalString("blocked_reason"),
    ),
    context,
  )

internal fun goalProseFinished(arguments: Map<String, Any?>, context: McpRuntimeContext): Map<String, Any?> =
  McpRuntime.goalProseFinished(
    GoalFinishedRequest(
      issueKey = arguments.string("issue_key"),
      workflowId = arguments.string("workflow_id"),
      status = arguments.string("status"),
      startedAt = arguments.string("started_at"),
      finishedAt = arguments.string("finished_at"),
      durationMs = arguments.int("duration_ms", 0).toLong(),
      subtasksComplete = arguments.int("subtasks_complete", 0),
      subtasksBlocked = arguments.int("subtasks_blocked", 0),
      subtasksSkipped = arguments.int("subtasks_skipped", 0),
      mode = "prose",
    ),
    context,
  )
