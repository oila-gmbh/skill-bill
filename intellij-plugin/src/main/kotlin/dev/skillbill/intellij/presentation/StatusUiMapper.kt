package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.GoalPlanningInfo
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import java.time.Duration
import java.time.Instant

/**
 * Exhaustive domain → UI mapping. Elapsed durations come only from authoritative
 * start timestamps via [now]; absent starts stay absent (never synthesized).
 */
object StatusUiMapper {
    fun map(outcome: SkillBillStatusOutcome, now: Instant): SkillBillStatusUiState =
        when (outcome) {
            is SkillBillStatusOutcome.Idle ->
                SkillBillStatusUiState.Idle(
                    headline = "Skill Bill: idle",
                    detail = outcome.summary,
                    lastUpdated = outcome.observedAt,
                    stale = outcome.stale,
                )

            is SkillBillStatusOutcome.Done ->
                SkillBillStatusUiState.Done(
                    headline = doneHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    startedAt = outcome.startedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    stale = outcome.stale,
                )

            is SkillBillStatusOutcome.Active ->
                SkillBillStatusUiState.Active(
                    headline = activeHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        now,
                    ),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, now),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    workflowId = outcome.workflowId,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt,
                    planning = relevantPlanning(
                        outcome.planning,
                        outcome.currentSubtaskId,
                        outcome.progressCompleted,
                    ),
                    workflowFamily = outcome.workflowFamily,
                    pauseRequested = outcome.pauseRequested,
                    currentModel = outcome.currentModel,
                    activeDurationMs = outcome.activeDurationMs,
                    activeDurationAsOf = outcome.activeDurationAsOf,
                )

            is SkillBillStatusOutcome.Paused ->
                SkillBillStatusUiState.Paused(
                    headline = pausedHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, settledAt(outcome.updatedAt, now)),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    workflowId = outcome.workflowId,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt,
                    planning = relevantPlanning(
                        outcome.planning,
                        outcome.currentSubtaskId,
                        outcome.progressCompleted,
                    ),
                    workflowFamily = outcome.workflowFamily,
                    pauseRequested = outcome.pauseRequested,
                    currentModel = outcome.currentModel,
                )

            is SkillBillStatusOutcome.Stale ->
                SkillBillStatusUiState.Stale(
                    headline = staleHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, settledAt(outcome.updatedAt, now)),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = if (outcome.fromCache) "Cached status is stale" else "Status is stale",
                    planning = relevantPlanning(
                        outcome.planning,
                        outcome.currentSubtaskId,
                        outcome.progressCompleted,
                    ),
                    // A Stale built from the persisted display cache carries no model — the cache
                    // deliberately stores none, so a phase's model is never resurrected from disk
                    // after a restart. The Model row is therefore absent until a live poll lands;
                    // that missing row is the cache being honest, not a parse failure.
                    currentModel = outcome.currentModel,
                )

            is SkillBillStatusOutcome.Blocked ->
                SkillBillStatusUiState.Blocked(
                    headline = "Skill Bill: blocked",
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, settledAt(outcome.updatedAt, now)),
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = outcome.summary,
                    stale = outcome.stale,
                    currentModel = outcome.currentModel,
                )

            is SkillBillStatusOutcome.Failed ->
                SkillBillStatusUiState.Failed(
                    headline = "Skill Bill: failed",
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, settledAt(outcome.updatedAt, now)),
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = outcome.summary,
                    stale = outcome.stale,
                    currentModel = outcome.currentModel,
                )

            is SkillBillStatusOutcome.Unavailable ->
                SkillBillStatusUiState.Unavailable(
                    headline = "Skill Bill: unavailable",
                    detail = outcome.summary,
                    reasonCode = outcome.reasonCode.name,
                    lastUpdated = outcome.observedAt,
                    problemSummary = "${outcome.reasonCode.name}: ${outcome.summary}",
                )

            is SkillBillStatusOutcome.Incompatible ->
                SkillBillStatusUiState.Incompatible(
                    headline = "Skill Bill: incompatible",
                    detail = outcome.summary,
                    foundContractVersion = outcome.foundContractVersion,
                    lastUpdated = outcome.observedAt,
                    problemSummary = buildString {
                        append("Contract mismatch")
                        outcome.foundContractVersion?.let { append(" (found ").append(it).append(')') }
                        append(": ").append(outcome.summary)
                    },
                )
        }

    /**
     * Anchor for work that is no longer running. Elapsed for a settled state is measured
     * to its last authoritative update, not to wall-clock now — otherwise an abandoned
     * goal reports an ever-growing duration that reads as ongoing execution. Falls back
     * to [now] only when no authoritative update exists.
     */
    fun settledAt(updatedAt: Instant?, now: Instant): Instant = updatedAt ?: now

    /**
     * Elapsed from an authoritative start. Returns null when start is absent.
     * Wall-clock rollback (now before start) yields [Duration.ZERO], never negative.
     */
    fun elapsed(startedAt: Instant?, now: Instant): Duration? {
        if (startedAt == null) return null
        val millis = now.toEpochMilli() - startedAt.toEpochMilli()
        return if (millis <= 0L) Duration.ZERO else Duration.ofMillis(millis)
    }

    /**
     * The goal clock. Prefers the runtime's accumulated execution time, which excludes the stretches
     * a goal spends blocked, paused, or simply unattended between runs — `now - startedAt` counts all
     * of those as work and reports an overnight gap as hours of progress.
     *
     * [asOf] is present only while a lease is live; the tail since then is added so the clock ticks
     * between polls, and the next heartbeat folds that same tail into [accumulatedMs] without
     * double counting. Falls back to wall clock when the snapshot carries no accumulated value,
     * which keeps older runtimes and non-goal families rendering exactly as before.
     */
    fun activeElapsed(
        accumulatedMs: Long?,
        asOf: Instant?,
        startedAt: Instant?,
        now: Instant,
    ): Duration? {
        if (accumulatedMs == null) return elapsed(startedAt, now)
        val tailMillis = asOf?.let { (now.toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0L) } ?: 0L
        return Duration.ofMillis(accumulatedMs + tailMillis)
    }

    /**
     * Re-anchors elapsed clocks from retained start timestamps without a new poll.
     *
     * Only live work ticks. Settled states (stale/blocked/failed) already carry a
     * duration frozen at their last authoritative update and are returned untouched;
     * ticking them would keep counting long after execution stopped.
     */
    fun withElapsed(state: SkillBillStatusUiState, now: Instant): SkillBillStatusUiState =
        when (state) {
            is SkillBillStatusUiState.Active -> state.copy(
                goalElapsed = activeElapsed(
                    state.activeDurationMs,
                    state.activeDurationAsOf,
                    state.startedAt,
                    now,
                ),
                subtaskElapsed = elapsed(state.subtaskStartedAt, now),
            )
            else -> state
        }

    /**
     * The one place planning relevance is decided, so bar, tooltip, and accessibility
     * text cannot diverge. Planning stops being relevant once implementation starts:
     * state `prepared`, or the snapshot already reports execution work. Snapshot-derived
     * with no latch — `progress.completed` only moves forward.
     */
    private fun relevantPlanning(
        planning: GoalPlanningInfo?,
        currentSubtaskId: String?,
        progressCompleted: Int?,
    ): GoalPlanningInfo? {
        if (planning == null || planning.state == "prepared") return null
        if (!currentSubtaskId.isNullOrBlank() || (progressCompleted ?: 0) > 0) return null
        return planning
    }

    private fun doneHeadline(outcome: SkillBillStatusOutcome.Done): String {
        val key = outcome.issueKey?.let { "$it · " }.orEmpty()
        return "Skill Bill: ${key}done"
    }

    private fun activeHeadline(outcome: SkillBillStatusOutcome.Active): String {
        val key = outcome.issueKey?.let { "$it · " }.orEmpty()
        return "Skill Bill: $key${outcome.currentStepLabel}"
    }

    private fun pausedHeadline(outcome: SkillBillStatusOutcome.Paused): String {
        val key = outcome.issueKey?.let { "$it · " }.orEmpty()
        return "Skill Bill: ${key}paused"
    }

    private fun staleHeadline(outcome: SkillBillStatusOutcome.Stale): String {
        val label = outcome.currentStepLabel
        return if (label.isNullOrBlank()) {
            "Skill Bill: stale"
        } else {
            "Skill Bill: $label (stale)"
        }
    }
}
