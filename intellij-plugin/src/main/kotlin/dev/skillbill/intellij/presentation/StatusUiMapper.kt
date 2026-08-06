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
                    stepLabel = outcome.currentStepLabel,
                )

            is SkillBillStatusOutcome.Stale ->
                SkillBillStatusUiState.Stale(
                    headline = staleHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = elapsed(outcome.startedAt, now),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, now),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                )

            is SkillBillStatusOutcome.Blocked ->
                SkillBillStatusUiState.Blocked(
                    headline = "Skill Bill: blocked",
                    detail = outcome.summary,
                    goalElapsed = elapsed(outcome.startedAt, now),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, now),
                )

            is SkillBillStatusOutcome.Failed ->
                SkillBillStatusUiState.Failed(
                    headline = "Skill Bill: failed",
                    detail = outcome.summary,
                    goalElapsed = elapsed(outcome.startedAt, now),
                    subtaskElapsed = elapsed(outcome.subtaskStartedAt, now),
                )

            is SkillBillStatusOutcome.Unavailable ->
                SkillBillStatusUiState.Unavailable(
                    headline = "Skill Bill: unavailable",
                    detail = outcome.summary,
                    reasonCode = outcome.reasonCode.name,
                )

            is SkillBillStatusOutcome.Incompatible ->
                SkillBillStatusUiState.Incompatible(
                    headline = "Skill Bill: incompatible",
                    detail = outcome.summary,
                    foundContractVersion = outcome.foundContractVersion,
                )
        }

    /**
     * Elapsed from an authoritative start. Returns null when start is absent.
     * Wall-clock rollback (now before start) yields [Duration.ZERO], never negative.
     */
    fun elapsed(startedAt: Instant?, now: Instant): Duration? {
        if (startedAt == null) return null
        val millis = now.toEpochMilli() - startedAt.toEpochMilli()
        return if (millis <= 0L) Duration.ZERO else Duration.ofMillis(millis)
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
