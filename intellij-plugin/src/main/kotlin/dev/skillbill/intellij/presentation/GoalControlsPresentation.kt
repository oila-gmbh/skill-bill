package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.FEATURE_GOAL_WORKFLOW_FAMILY

/**
 * The single place goal-control eligibility and labelling are decided.
 *
 * Pure function of one snapshot-derived UI state: no clock, no memory of local clicks,
 * no IntelliJ or Swing types. Consumers render what this returns and never re-derive
 * eligibility, so a control can never appear on a state the snapshot does not support.
 */
object GoalControlsPresentation {
    fun controlsFor(state: SkillBillStatusUiState): List<GoalControlDescriptor> {
        // Only a running feature-goal with an issue key can be stopped or paused.
        if (state !is SkillBillStatusUiState.Active) return emptyList()
        if (state.workflowFamily != FEATURE_GOAL_WORKFLOW_FAMILY) return emptyList()
        val issueKey = state.issueKey?.takeIf { it.isNotBlank() } ?: return emptyList()

        // Absent means the snapshot said nothing about a pending pause; only an
        // explicit true is evidence of a request the runtime has yet to consume.
        val pauseAlreadyRequested = state.pauseRequested == true
        return listOf(
            GoalControlDescriptor(
                kind = GoalControlKind.STOP,
                issueKey = issueKey,
                text = "Stop goal",
                enabled = true,
                accessibleName = "Stop Skill Bill goal $issueKey now",
            ),
            GoalControlDescriptor(
                kind = GoalControlKind.PAUSE,
                issueKey = issueKey,
                // A registered request is not a pause: the runtime consumes it at the
                // next boundary, so the text must not claim the goal is already paused.
                text = if (pauseAlreadyRequested) "Pause requested" else "Pause after current subtask",
                enabled = !pauseAlreadyRequested,
                accessibleName = if (pauseAlreadyRequested) {
                    "Pause already requested for Skill Bill goal $issueKey; it takes effect after the current subtask"
                } else {
                    "Pause Skill Bill goal $issueKey after the current subtask"
                },
            ),
        )
    }
}

enum class GoalControlKind {
    STOP,
    PAUSE,
}

/** One rendered control. [issueKey] is the snapshot's key the action must target. */
data class GoalControlDescriptor(
    val kind: GoalControlKind,
    val issueKey: String,
    val text: String,
    val enabled: Boolean,
    val accessibleName: String,
)
