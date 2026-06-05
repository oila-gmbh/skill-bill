package skillbill.application

import skillbill.application.model.GoalFinishedRequest
import skillbill.application.model.GoalStartedRequest
import skillbill.application.model.GoalSubtaskFinishedRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.workflow.model.DecompositionManifest
import java.time.Clock
import java.time.Duration
import java.time.Instant

// SKILL-66 Subtask 3 (AC3): application seam for goal lifecycle telemetry
// emission. GoalRunner depends on this interface only, never on the
// persistence/MCP/Clikt/JDBC concretes that back it (RuntimeArchitectureTest
// boundary gate). LifecycleTelemetryService is the production implementation;
// NONE is the default/test no-op so the positional GoalRunner test constructors
// keep compiling and AC5 byte-equivalence holds for runs without telemetry.
//
// SKILL-66 Subtask 3 (AC4): the production implementation propagates an enabled
// write failure out of these methods — they do not swallow, retry, or downgrade
// a telemetry failure to a log line. See decisions.md (2026-06-05).
interface GoalLifecycleTelemetryEmitter {
  fun goalStarted(request: GoalStartedRequest, dbOverride: String?)

  fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?)

  fun goalFinished(request: GoalFinishedRequest, dbOverride: String?)

  companion object {
    val NONE: GoalLifecycleTelemetryEmitter = object : GoalLifecycleTelemetryEmitter {
      override fun goalStarted(request: GoalStartedRequest, dbOverride: String?) = Unit

      override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?) = Unit

      override fun goalFinished(request: GoalFinishedRequest, dbOverride: String?) = Unit
    }
  }
}

/**
 * SKILL-66 Subtask 3: per-run goal lifecycle telemetry collaborator, beside
 * [GoalRunnerObservabilityEmitter] / [GoalRunnerLedgerRecorder] and following
 * that decomposition style.
 *
 * It emits exactly one `goal_started` per run segment, exactly one
 * `goal_subtask_finished` per subtask reaching terminal status within the
 * current segment (never for subtasks already terminal when the segment began),
 * and exactly one `goal_finished` per segment at terminal outcome.
 *
 * All timing derives from the injected [clock] seam — no agent-supplied timing
 * enters any payload (AC2). The per-segment run-session id
 * (`<parentWorkflowId>:seg:<segmentStartedAt>`) is unique per segment and never
 * collides with the stable child `wfl-N` ids, so the per-segment emit-once
 * guard holds across resume segments (AC1/AC6).
 */
internal class GoalRunnerTelemetryEmitter(
  private val telemetry: GoalLifecycleTelemetryEmitter,
  private val clock: Clock,
  private val state: GoalRunnerManifestState,
  private val dbPathOverride: String?,
) {
  private val segmentStartedAt: String = clock.instant().toString()
  private val segmentWorkflowId: String = "${state.parentWorkflowId}:seg:$segmentStartedAt"
  private val resumed: Boolean = state.manifest.subtasks.any { it.hasStarted() }

  // Subtasks already terminal when this segment began: a resumed run must never
  // re-emit goal_subtask_finished for work completed in an earlier segment.
  private val priorTerminal: Set<Int> = state.manifest.subtasks
    .filter { it.status in TERMINAL_STATUSES }
    .map { it.id }
    .toSet()
  private val emittedThisSegment: MutableSet<Int> = mutableSetOf()
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
      ),
      dbPathOverride,
    )
  }

  fun markSubtaskStarted(subtaskId: Int) {
    subtaskStartedAt.putIfAbsent(subtaskId, clock.instant().toString())
  }

  // Centralized transition-detector: emit exactly one goal_subtask_finished for
  // each subtask that is terminal now, was not terminal when the segment began,
  // and has not already been emitted this segment. This uniformly covers
  // complete, blocked, and projection-driven skipped — the last of which no
  // per-emit-site hook in the loop could catch (the loop never sets "skipped").
  fun sweepTerminal(manifest: DecompositionManifest, attempted: List<Int>) {
    val finishedAtInstant = clock.instant()
    val finishedAt = finishedAtInstant.toString()
    manifest.subtasks
      .filter { it.status in TERMINAL_STATUSES }
      .filter { it.id !in priorTerminal && it.id !in emittedThisSegment }
      .forEach { subtask ->
        emittedThisSegment += subtask.id
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
          ),
          dbPathOverride,
        )
      }
  }

  fun goalFinished(manifest: DecompositionManifest, report: GoalRunnerRunReport) {
    val finishedAtInstant = clock.instant()
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
