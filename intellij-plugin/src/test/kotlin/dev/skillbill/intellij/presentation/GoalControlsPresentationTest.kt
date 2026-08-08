package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.FEATURE_GOAL_WORKFLOW_FAMILY
import dev.skillbill.intellij.fakes.activeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eligibility is proved exhaustively rather than by sampling: every UI state subtype is
 * crossed with each workflow family and each issue-key shape.
 */
class GoalControlsPresentationTest {
    private val families = listOf(FEATURE_GOAL_WORKFLOW_FAMILY, "feature-task-runtime", null)
    private val issueKeys = listOf("SKILL-168", null, "   ")

    @Test
    fun `controls appear in exactly the one eligible combination`() {
        var eligibleCount = 0
        for (family in families) {
            for (issueKey in issueKeys) {
                for (state in allStates(family, issueKey)) {
                    val controls = GoalControlsPresentation.controlsFor(state)
                    val shouldBeEligible = state is SkillBillStatusUiState.Active &&
                        family == FEATURE_GOAL_WORKFLOW_FAMILY &&
                        issueKey == "SKILL-168"
                    if (shouldBeEligible) {
                        eligibleCount++
                        assertEquals(
                            "expected both controls for $state",
                            listOf(GoalControlKind.STOP, GoalControlKind.PAUSE),
                            controls.map { it.kind },
                        )
                        assertTrue(controls.all { it.accessibleName.isNotBlank() })
                        assertTrue(controls.all { it.issueKey == "SKILL-168" })
                    } else {
                        assertTrue(
                            "controls must be absent for family=$family key=$issueKey state=${state::class.simpleName}",
                            controls.isEmpty(),
                        )
                    }
                }
            }
        }
        assertEquals("exactly one combination is eligible", 1, eligibleCount)
    }

    @Test
    fun `pause renders disabled for a CLI-originated request with no local click`() {
        val controls = GoalControlsPresentation.controlsFor(activeUiState(pauseRequested = true))
        val pause = controls.single { it.kind == GoalControlKind.PAUSE }
        assertFalse("a requested pause disables the control", pause.enabled)
        assertTrue("text must report the registered request", pause.text.contains("requested", ignoreCase = true))
        assertTrue(pause.accessibleName.isNotBlank())
    }

    @Test
    fun `pause renders disabled on the first snapshot after restart`() {
        // No prior state exists after a restart: the very first snapshot carries the flag.
        val firstSnapshot = activeUiState(pauseRequested = true)
        val pause = GoalControlsPresentation.controlsFor(firstSnapshot).single { it.kind == GoalControlKind.PAUSE }
        assertFalse(pause.enabled)
    }

    @Test
    fun `disabled pause never asserts the goal is already paused`() {
        val pause = GoalControlsPresentation.controlsFor(activeUiState(pauseRequested = true))
            .single { it.kind == GoalControlKind.PAUSE }
        val claimsPaused = Regex("\\bis paused\\b|\\bgoal paused\\b|^Paused$", RegexOption.IGNORE_CASE)
        assertFalse("text must not claim the goal is paused: ${pause.text}", claimsPaused.containsMatchIn(pause.text))
        assertFalse(
            "accessible name must not claim the goal is paused: ${pause.accessibleName}",
            claimsPaused.containsMatchIn(pause.accessibleName),
        )
    }

    @Test
    fun `an absent pause flag leaves the control enabled`() {
        val pause = GoalControlsPresentation.controlsFor(activeUiState(pauseRequested = null))
            .single { it.kind == GoalControlKind.PAUSE }
        assertTrue(pause.enabled)
        assertNotNull(pause.accessibleName)
        assertTrue(
            "enabled text names the boundary effect: ${pause.text}",
            pause.text.contains("subtask", ignoreCase = true),
        )
    }

    private fun allStates(family: String?, issueKey: String?): List<SkillBillStatusUiState> = listOf(
        activeUiState(issueKey = issueKey, workflowFamily = family),
        SkillBillStatusUiState.Idle(),
        SkillBillStatusUiState.Done(headline = "done", issueKey = issueKey),
        SkillBillStatusUiState.Paused(
            headline = "paused",
            detail = null,
            goalElapsed = null,
            subtaskElapsed = null,
            progressCompleted = null,
            progressTotal = null,
            issueKey = issueKey,
            workflowId = "w1",
            stepLabel = "Implement",
            startedAt = null,
            subtaskStartedAt = null,
            lastUpdated = null,
            workflowFamily = family,
        ),
        SkillBillStatusUiState.Stale(
            headline = "stale",
            detail = null,
            goalElapsed = null,
            subtaskElapsed = null,
            progressCompleted = null,
            progressTotal = null,
            issueKey = issueKey,
        ),
        SkillBillStatusUiState.Blocked(
            headline = "blocked",
            detail = null,
            goalElapsed = null,
            subtaskElapsed = null,
            issueKey = issueKey,
        ),
        SkillBillStatusUiState.Failed(
            headline = "failed",
            detail = null,
            goalElapsed = null,
            subtaskElapsed = null,
            issueKey = issueKey,
        ),
        SkillBillStatusUiState.Unavailable(headline = "unavailable", detail = null, reasonCode = "TIMEOUT"),
        SkillBillStatusUiState.Incompatible(headline = "incompatible", detail = null, foundContractVersion = "0.9"),
    )
}
