package dev.skillbill.intellij.presentation

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillBillStatusBarPresentationTest {
    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val started = Instant.parse("2026-08-07T10:00:00Z")
    private val subtaskStarted = Instant.parse("2026-08-07T11:30:00Z")

    @Test
    fun `active bar includes step dual elapsed and valid progress`() {
        val mapped = SkillBillStatusBarPresentation.map(active(), now)
        assertTrue(mapped.barText.startsWith("Skill Bill · Implement"))
        assertTrue(mapped.barText.contains("2h 00m"))
        assertTrue(mapped.barText.contains("30m 00s"))
        assertTrue(mapped.barText.contains("3/9"))
        assertTrue(mapped.showActivityAnimation)
        assertFalse(mapped.isStaleMarked)
    }

    @Test
    fun `active bar omits progress when total is invalid`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(progressCompleted = 2, progressTotal = 0),
            now,
        )
        assertFalse(mapped.barText.contains("/"))
        assertEquals(null, mapped.details.progressText)
    }

    @Test
    fun `active bar omits progress when completed exceeds total`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(progressCompleted = 5, progressTotal = 3),
            now,
        )
        assertEquals(null, mapped.details.progressText)
    }

    @Test
    fun `missing timestamps render unavailable not zero`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(goalElapsed = null, subtaskElapsed = null, startedAt = null, subtaskStartedAt = null),
            now,
        )
        assertTrue(mapped.barText.contains(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED))
        assertEquals(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED, mapped.details.goalElapsedText)
        assertEquals(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED, mapped.details.subtaskElapsedText)
        assertFalse(mapped.barText.contains("0s"))
    }

    @Test
    fun `clock skew clamp to zero renders as zero duration not negative`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(
                startedAt = now.plusSeconds(30),
                subtaskStartedAt = now.plusSeconds(30),
            ),
            now,
        )
        assertEquals("0s", mapped.details.goalElapsedText)
        assertEquals("0s", mapped.details.subtaskElapsedText)
        assertFalse(mapped.details.goalElapsedText.contains("-"))
    }

    @Test
    fun `truncates long unsafe labels on bar and keeps full context in tooltip`() {
        val longStep = "Implement ".repeat(20) + "\u0007secret"
        val state = active(stepLabel = longStep, headline = "Skill Bill: $longStep")
        val mapped = SkillBillStatusBarPresentation.map(state, now)
        assertTrue(mapped.barText.length <= SkillBillStatusBarPresentation.BAR_TEXT_MAX_LENGTH)
        assertTrue(mapped.barText.endsWith("…"))
        assertFalse(mapped.barText.contains("\u0007"))
        assertTrue(mapped.tooltipText.contains("Implement"))
        assertTrue(mapped.tooltipText.length > mapped.barText.length)
    }

    @Test
    fun `distinct concise text for every non-active lifecycle`() {
        val cases = listOf(
            SkillBillStatusUiState.Idle() to "Skill Bill · idle",
            SkillBillStatusUiState.Blocked(
                headline = "Skill Bill: blocked",
                detail = "needs input",
                goalElapsed = null,
                subtaskElapsed = null,
                problemSummary = "needs input",
            ) to "Skill Bill · blocked",
            SkillBillStatusUiState.Failed(
                headline = "Skill Bill: failed",
                detail = "boom",
                goalElapsed = null,
                subtaskElapsed = null,
                problemSummary = "boom",
            ) to "Skill Bill · failed",
            SkillBillStatusUiState.Unavailable(
                headline = "Skill Bill: unavailable",
                detail = "missing cli",
                reasonCode = "MISSING_EXECUTABLE",
                problemSummary = "MISSING_EXECUTABLE: missing cli",
            ) to "Skill Bill · unavailable",
            SkillBillStatusUiState.Incompatible(
                headline = "Skill Bill: incompatible",
                detail = "bad contract",
                foundContractVersion = "9.9",
                problemSummary = "Contract mismatch",
            ) to "Skill Bill · incompatible",
            SkillBillStatusUiState.Stale(
                headline = "Skill Bill: Implement (stale)",
                detail = "cached",
                goalElapsed = Duration.ofMinutes(5),
                subtaskElapsed = null,
                progressCompleted = null,
                progressTotal = null,
                stepLabel = "Implement",
            ) to "Skill Bill · stale",
        )
        for ((state, expectedPrefix) in cases) {
            val mapped = SkillBillStatusBarPresentation.map(state, now)
            assertTrue("$expectedPrefix in ${mapped.barText}", mapped.barText.startsWith(expectedPrefix))
            assertFalse(mapped.showActivityAnimation)
            assertTrue(mapped.tooltipText.contains("Skill Bill"))
        }
    }

    @Test
    fun `stale is visibly marked and never animates`() {
        val mapped = SkillBillStatusBarPresentation.map(
            SkillBillStatusUiState.Stale(
                headline = "Skill Bill: Implement (stale)",
                detail = "cached",
                goalElapsed = Duration.ofMinutes(1),
                subtaskElapsed = Duration.ofSeconds(30),
                progressCompleted = null,
                progressTotal = null,
                stepLabel = "Implement",
            ),
            now,
        )
        assertTrue(mapped.isStaleMarked)
        assertFalse(mapped.showActivityAnimation)
        assertTrue(mapped.tooltipText.contains("Stale"))
    }

    @Test
    fun `accessibility includes Skill Bill state step both clocks and progress`() {
        val mapped = SkillBillStatusBarPresentation.map(active(), now)
        assertTrue(mapped.accessibleName.contains("Skill Bill"))
        assertTrue(mapped.accessibleName.contains("active"))
        assertTrue(mapped.accessibleDescription.contains("Skill Bill"))
        assertTrue(mapped.accessibleDescription.contains("active"))
        assertTrue(mapped.accessibleDescription.contains("Implement"))
        assertTrue(mapped.accessibleDescription.contains("Goal elapsed"))
        assertTrue(mapped.accessibleDescription.contains("Subtask elapsed"))
        assertTrue(mapped.accessibleDescription.contains("3/9"))
    }

    @Test
    fun `details carry safe issue workflow state step progress clocks last update and problem`() {
        val mapped = SkillBillStatusBarPresentation.map(active(), now)
        val d = mapped.details
        assertEquals("SKILL-148", d.issueKey)
        assertEquals("wfl-1", d.workflowId)
        assertEquals("active", d.lifecycleState)
        assertEquals("Implement", d.stepLabel)
        assertEquals("3/9", d.progressText)
        assertEquals("2h 00m", d.goalElapsedText)
        assertEquals("30m 00s", d.subtaskElapsedText)
        assertTrue(d.lastUpdateText!!.contains("2026-08-07"))
        assertEquals("working", d.problemSummary)
    }

    @Test
    fun `validProgress rejects null negative and inverted bounds`() {
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(null, 3))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(1, null))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(-1, 3))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(1, 0))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(4, 3))
        assertEquals(1 to 3, SkillBillStatusBarPresentation.validProgress(1, 3))
    }

    @Test
    fun `re-anchor via now advances elapsed from startedAt without synthesizing`() {
        val state = active(
            goalElapsed = Duration.ofSeconds(1),
            subtaskElapsed = null,
            startedAt = started,
            subtaskStartedAt = null,
        )
        val later = started.plusSeconds(90)
        val mapped = SkillBillStatusBarPresentation.map(state, later)
        assertEquals("1m 30s", mapped.details.goalElapsedText)
        assertEquals(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED, mapped.details.subtaskElapsedText)
    }

    private fun active(
        stepLabel: String = "Implement",
        headline: String = "Skill Bill: SKILL-148 · Implement",
        goalElapsed: Duration? = Duration.ofHours(2),
        subtaskElapsed: Duration? = Duration.ofMinutes(30),
        progressCompleted: Int? = 3,
        progressTotal: Int? = 9,
        startedAt: Instant? = started,
        subtaskStartedAt: Instant? = subtaskStarted,
    ) = SkillBillStatusUiState.Active(
        headline = headline,
        detail = "working",
        goalElapsed = goalElapsed,
        subtaskElapsed = subtaskElapsed,
        progressCompleted = progressCompleted,
        progressTotal = progressTotal,
        issueKey = "SKILL-148",
        workflowId = "wfl-1",
        stepLabel = stepLabel,
        startedAt = startedAt,
        subtaskStartedAt = subtaskStartedAt,
        lastUpdated = now,
    )
}
