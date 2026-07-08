package skillbill.application.goalrunner

import skillbill.application.model.GoalFinishedRequest
import skillbill.application.model.GoalIssueFinishedRequest
import skillbill.application.model.GoalStartedRequest
import skillbill.application.model.GoalSubtaskFinishedRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.workflow.model.DecompositionManifest
import java.time.Clock
import java.time.Duration
import java.time.Instant

interface GoalLifecycleTelemetryEmitter {
  fun goalStarted(request: GoalStartedRequest, dbOverride: String?)

  fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?)

  fun goalFinished(request: GoalFinishedRequest, dbOverride: String?)

  fun goalIssueFinished(request: GoalIssueFinishedRequest, dbOverride: String?)

  companion object {
    val NONE: GoalLifecycleTelemetryEmitter = object : GoalLifecycleTelemetryEmitter {
      override fun goalStarted(request: GoalStartedRequest, dbOverride: String?) = Unit

      override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?) = Unit

      override fun goalFinished(request: GoalFinishedRequest, dbOverride: String?) = Unit

      override fun goalIssueFinished(request: GoalIssueFinishedRequest, dbOverride: String?) = Unit
    }
  }
}

internal class GoalRunnerTelemetryEmitter(
  private val telemetry: GoalLifecycleTelemetryEmitter,
  private val clock: Clock,
  private val state: GoalRunnerManifestState,
  private val dbPathOverride: String?,
) {
  private val segmentStartedAt: String = clock.instant().toString()
  private val segmentWorkflowId: String = "${state.parentWorkflowId}:seg:$segmentStartedAt"
  private val resumed: Boolean = state.manifest.subtasks.any { it.hasStarted() }

  private val subtasksTerminalAtSegmentStart: Set<Int> = state.manifest.subtasks
    .filter { it.status in TERMINAL_STATUSES }
    .map { it.id }
    .toSet()
  private val subtasksEmittedThisSegment: MutableSet<Int> = mutableSetOf()
  private val subtaskStartedAt: MutableMap<Int, String> = mutableMapOf()

  fun goalStarted() {
    telemetry.goalStarted(
      GoalStartedRequest(
        issueKey = state.manifest.issueKey,
        featureName = state.manifest.featureName,
        workflowId = segmentWorkflowId,
        subtaskTotal = state.manifest.subtasks.size,
        resumed = resumed,
        startedAt = segmentStartedAt,
        mode = "runtime",
        parentWorkflowId = state.parentWorkflowId,
      ),
      dbPathOverride,
    )
  }

  fun markSubtaskStarted(subtaskId: Int) {
    subtaskStartedAt.putIfAbsent(subtaskId, clock.instant().toString())
  }

  fun emitNewlyTerminalSubtasks(manifest: DecompositionManifest, attempted: List<Int>) {
    val finishedAtInstant = clock.instant()
    val finishedAt = finishedAtInstant.toString()
    manifest.subtasks
      .filter { it.status in TERMINAL_STATUSES }
      .filter { it.id !in subtasksTerminalAtSegmentStart && it.id !in subtasksEmittedThisSegment }
      .forEach { subtask ->
        subtasksEmittedThisSegment += subtask.id
        val startedAt = subtaskStartedAt[subtask.id] ?: finishedAt
        telemetry.goalSubtaskFinished(
          GoalSubtaskFinishedRequest(
            issueKey = manifest.issueKey,
            workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
              ?: "${manifest.issueKey}:subtask:${subtask.id}",
            subtaskId = subtask.id,
            subtaskName = subtask.name,
            status = subtask.status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = durationMs(startedAt, finishedAtInstant),
            attemptCount = attempted.count { it == subtask.id }.coerceAtLeast(1),
            blockedReason = subtask.blockedReason,
            finalizingAgentId = subtask.finalizingAgentId,
            participatingAgentIds = subtask.participatingAgentIds,
          ),
          dbPathOverride,
        )
      }
  }

  fun goalFinished(manifest: DecompositionManifest, report: GoalRunnerRunReport) {
    val finishedAtInstant = clock.instant()
    val stopReason = (report as? GoalRunnerRunReport.Stopped)?.stop?.reason?.name
    telemetry.goalFinished(
      GoalFinishedRequest(
        issueKey = manifest.issueKey,
        workflowId = segmentWorkflowId,
        status = if (report is GoalRunnerRunReport.Completed) "completed" else "blocked",
        startedAt = segmentStartedAt,
        finishedAt = finishedAtInstant.toString(),
        durationMs = durationMs(segmentStartedAt, finishedAtInstant),
        subtasksComplete = manifest.subtasks.count { it.status == "complete" },
        subtasksBlocked = manifest.subtasks.count { it.status == "blocked" },
        subtasksSkipped = manifest.subtasks.count { it.status == "skipped" },
        mode = "runtime",
        stopReason = stopReason,
        parentWorkflowId = state.parentWorkflowId,
      ),
      dbPathOverride,
    )
  }

  fun goalIssueFinished(manifest: DecompositionManifest, report: GoalRunnerRunReport.Completed) {
    telemetry.goalIssueFinished(
      GoalIssueFinishedRequest(
        issueKey = manifest.issueKey,
        parentWorkflowId = state.parentWorkflowId,
        status = "completed",
        subtasksComplete = report.subtasksCompleted,
        subtasksBlocked = report.subtasksBlocked,
        subtasksSkipped = manifest.subtasks.count { it.status == "skipped" },
        finishedAt = clock.instant().toString(),
        mode = "runtime",
      ),
      dbPathOverride,
    )
  }

  private fun durationMs(startedAt: String, finishedAt: Instant): Long =
    Duration.between(Instant.parse(startedAt), finishedAt).toMillis().coerceAtLeast(0)

  private companion object {
    val TERMINAL_STATUSES: Set<String> = setOf("complete", "blocked", "skipped")
  }
}
