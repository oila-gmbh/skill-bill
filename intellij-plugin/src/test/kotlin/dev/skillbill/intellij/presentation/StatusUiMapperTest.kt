package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.UnavailableReason
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusUiMapperTest {
    private val now = Instant.parse("2026-08-06T12:00:00Z")
    private val started = Instant.parse("2026-08-06T10:00:00Z")
    private val subtaskStarted = Instant.parse("2026-08-06T11:00:00Z")

    @Test
    fun `maps every domain outcome exhaustively`() {
        val outcomes = listOf(
            SkillBillStatusOutcome.Idle(now, "idle"),
            active(),
            SkillBillStatusOutcome.Stale(
                observedAt = now,
                summary = "stale",
                repositoryIdentity = "repo",
                issueKey = "SKILL-148",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                progressCompleted = 1,
                progressTotal = 3,
                startedAt = started,
                currentSubtaskId = "2",
                subtaskStartedAt = subtaskStarted,
                updatedAt = now,
            ),
            SkillBillStatusOutcome.Blocked(
                observedAt = now,
                summary = "blocked",
                repositoryIdentity = "repo",
                issueKey = "SKILL-148",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                startedAt = started,
                currentSubtaskId = "2",
                subtaskStartedAt = subtaskStarted,
                updatedAt = now,
            ),
            SkillBillStatusOutcome.Failed(
                observedAt = now,
                summary = "failed",
                repositoryIdentity = "repo",
                issueKey = null,
                currentStepId = null,
                currentStepLabel = null,
                startedAt = null,
                currentSubtaskId = null,
                subtaskStartedAt = null,
                updatedAt = now,
            ),
            SkillBillStatusOutcome.Unavailable(
                observedAt = now,
                summary = "missing cli",
                reasonCode = UnavailableReason.MISSING_EXECUTABLE,
            ),
            SkillBillStatusOutcome.Incompatible(
                observedAt = now,
                summary = "bad contract",
                foundContractVersion = "9.9",
            ),
        )
        val mapped = outcomes.map { StatusUiMapper.map(it, now)::class }
        assertEquals(
            listOf(
                SkillBillStatusUiState.Idle::class,
                SkillBillStatusUiState.Active::class,
                SkillBillStatusUiState.Stale::class,
                SkillBillStatusUiState.Blocked::class,
                SkillBillStatusUiState.Failed::class,
                SkillBillStatusUiState.Unavailable::class,
                SkillBillStatusUiState.Incompatible::class,
            ),
            mapped,
        )
    }

    @Test
    fun `elapsed ticking is deterministic from injected clock`() {
        val t0 = Instant.parse("2026-08-06T10:00:00Z")
        val t1 = Instant.parse("2026-08-06T10:00:30Z")
        assertEquals(Duration.ofSeconds(30), StatusUiMapper.elapsed(t0, t1))
        val ui = StatusUiMapper.map(active(startedAt = t0, subtaskStartedAt = t0), t1)
        assertTrue(ui is SkillBillStatusUiState.Active)
        ui as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(30), ui.goalElapsed)
        assertEquals(Duration.ofSeconds(30), ui.subtaskElapsed)
    }

    @Test
    fun `wall-clock rollback yields zero elapsed not negative`() {
        val start = Instant.parse("2026-08-06T12:00:00Z")
        val earlier = Instant.parse("2026-08-06T11:00:00Z")
        assertEquals(Duration.ZERO, StatusUiMapper.elapsed(start, earlier))
    }

    @Test
    fun `absent legacy start timestamps stay absent`() {
        val ui = StatusUiMapper.map(
            active(startedAt = null, subtaskStartedAt = null),
            now,
        ) as SkillBillStatusUiState.Active
        assertNull(ui.goalElapsed)
        assertNull(ui.subtaskElapsed)
    }

    @Test
    fun `withElapsed re-anchors from startedAt and leaves absent subtask absent`() {
        val start = Instant.parse("2026-08-06T10:00:00Z")
        val t1 = Instant.parse("2026-08-06T10:01:00Z")
        val active = StatusUiMapper.map(active(startedAt = start, subtaskStartedAt = null), start)
            as SkillBillStatusUiState.Active
        val reanchored = StatusUiMapper.withElapsed(active, t1) as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(60), reanchored.goalElapsed)
        assertNull(reanchored.subtaskElapsed)
        assertEquals(start, reanchored.startedAt)
    }

    @Test
    fun `settled elapsed is frozen at last update and never ticks with wall clock`() {
        val lastUpdate = Instant.parse("2026-08-06T10:15:00Z")
        val muchLater = Instant.parse("2026-08-09T10:15:00Z")
        val blocked = SkillBillStatusOutcome.Blocked(
            observedAt = muchLater,
            summary = "blocked",
            repositoryIdentity = "repo",
            issueKey = "SKILL-148",
            currentStepId = "implement",
            currentStepLabel = "Implement",
            startedAt = started,
            currentSubtaskId = "2",
            subtaskStartedAt = subtaskStarted,
            updatedAt = lastUpdate,
        )
        val ui = StatusUiMapper.map(blocked, muchLater) as SkillBillStatusUiState.Blocked
        // 10:00 start → 10:15 last update, not → observation three days later.
        assertEquals(Duration.ofMinutes(15), ui.goalElapsed)
        assertEquals(ui, StatusUiMapper.withElapsed(ui, muchLater.plusSeconds(3_600)))
    }

    @Test
    fun `stale elapsed is frozen at last update`() {
        val lastUpdate = Instant.parse("2026-08-06T10:20:00Z")
        val muchLater = Instant.parse("2026-08-09T10:20:00Z")
        val stale = SkillBillStatusOutcome.Stale(
            observedAt = muchLater,
            summary = "stale",
            repositoryIdentity = "repo",
            issueKey = "SKILL-148",
            currentStepId = "implement",
            currentStepLabel = "Implement",
            progressCompleted = 0,
            progressTotal = 1,
            startedAt = started,
            currentSubtaskId = "2",
            subtaskStartedAt = null,
            updatedAt = lastUpdate,
        )
        val ui = StatusUiMapper.map(stale, muchLater) as SkillBillStatusUiState.Stale
        assertEquals(Duration.ofMinutes(20), ui.goalElapsed)
        assertEquals(ui, StatusUiMapper.withElapsed(ui, muchLater.plusSeconds(3_600)))
    }

    private fun active(
        startedAt: Instant? = started,
        subtaskStartedAt: Instant? = subtaskStarted,
    ) = SkillBillStatusOutcome.Active(
        observedAt = now,
        summary = "active",
        repositoryIdentity = "repo-root-realpath-v1:/repo",
        issueKey = "SKILL-148",
        workflowId = "wfl-1",
        workflowFamily = "feature-task-runtime",
        currentStepId = "implement",
        currentStepLabel = "Implement",
        progressCompleted = 3,
        progressTotal = 9,
        startedAt = startedAt,
        currentSubtaskId = "2",
        subtaskStartedAt = subtaskStartedAt,
        updatedAt = now,
    )
}
