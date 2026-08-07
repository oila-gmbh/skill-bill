package dev.skillbill.intellij.presentation

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

            is SkillBillStatusOutcome.Active ->
                SkillBillStatusUiState.Active(
                    headline = activeHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = elapsed(outcome.startedAt, now),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, now),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    workflowId = outcome.workflowId,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt,
                )

            is SkillBillStatusOutcome.Stale ->
                SkillBillStatusUiState.Stale(
                    headline = staleHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = elapsed(outcome.startedAt, settledAt(outcome.updatedAt, now)),
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
                )

            is SkillBillStatusOutcome.Blocked ->
                SkillBillStatusUiState.Blocked(
                    headline = "Skill Bill: blocked",
                    detail = outcome.summary,
                    goalElapsed = elapsed(outcome.startedAt, settledAt(outcome.updatedAt, now)),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, settledAt(outcome.updatedAt, now)),
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = outcome.summary,
                    stale = outcome.stale,
                )

            is SkillBillStatusOutcome.Failed ->
                SkillBillStatusUiState.Failed(
                    headline = "Skill Bill: failed",
                    detail = outcome.summary,
                    goalElapsed = elapsed(outcome.startedAt, settledAt(outcome.updatedAt, now)),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, settledAt(outcome.updatedAt, now)),
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = outcome.summary,
                    stale = outcome.stale,
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
     * Re-anchors elapsed clocks from retained start timestamps without a new poll.
     *
     * Only live work ticks. Settled states (stale/blocked/failed) already carry a
     * duration frozen at their last authoritative update and are returned untouched;
     * ticking them would keep counting long after execution stopped.
     */
    fun withElapsed(state: SkillBillStatusUiState, now: Instant): SkillBillStatusUiState =
        when (state) {
            is SkillBillStatusUiState.Active -> state.copy(
                goalElapsed = elapsed(state.startedAt, now),
                subtaskElapsed = elapsed(state.subtaskStartedAt, now),
            )
            else -> state
        }

    private fun activeHeadline(outcome: SkillBillStatusOutcome.Active): String {
        val key = outcome.issueKey?.let { "$it · " }.orEmpty()
        return "Skill Bill: $key${outcome.currentStepLabel}"
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
