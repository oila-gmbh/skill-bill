package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.FEATURE_GOAL_WORKFLOW_FAMILY
import dev.skillbill.intellij.domain.MODEL_TEXT_MAX_LENGTH
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure status-bar text / tooltip / accessibility / progress mapping.
 * No IntelliJ UI, process, or CLI code.
 */
object SkillBillStatusBarPresentation {
    const val BAR_TEXT_MAX_LENGTH: Int = 48
    const val UNAVAILABLE_ELAPSED: String = "—"
    const val STALE_NOTE: String = "(Stale — not live)"

    private val lastUpdateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun map(state: SkillBillStatusUiState, now: Instant? = null): MappedPresentation {
        val anchored = if (now == null) state else StatusUiMapper.withElapsed(state, now)
        val lifecycle = lifecycleLabel(anchored)
        val step = normalizeLabel(anchored.stepLabel)
        val goalText = elapsedLabel(anchored.goalElapsed)
        val subtaskText = elapsedLabel(anchored.subtaskElapsed)
        val progress = validProgress(anchored.progressCompleted, anchored.progressTotal)
        val progressText = progress?.let { (completed, total) ->
            "${renderedPosition(anchored, completed, total)}/$total"
        }
        val planning = anchored.planning
        // Popup-only: the bar, tooltip, and accessibility text stay byte-identical.
        // The phase is appended for the goal family only, where the Step row shows a goal-level
        // label (often "Planning") and would otherwise leave the model attributed to nothing. For
        // the runtime family the Step row already names that phase.
        val modelText = anchored.currentModel?.let { current ->
            val qualifier = listOfNotNull(
                current.phaseId?.takeIf { anchored.workflowFamily == FEATURE_GOAL_WORKFLOW_FAMILY },
                current.effort?.let { "effort: $it" },
            )
            val composed = if (qualifier.isEmpty()) {
                current.model
            } else {
                "${current.model} (${qualifier.joinToString(", ")})"
            }
            // Unlike barText this lands in a non-wrapping JLabel inside a popup with no width cap,
            // so an unbounded value widens the whole popup and can clip the action row off-screen.
            normalizeLabel(composed)?.let { truncateForBar(it, MODEL_TEXT_MAX_LENGTH) }
        }
        val planningSegment = planning?.let { "Planning ${it.plannedSubtaskCount}/${it.totalSubtaskCount}" }

        val fullBar = when (anchored) {
            is SkillBillStatusUiState.Active ->
                buildRunBar("Skill Bill", step ?: anchored.stepLabel, planningSegment, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Paused ->
                buildRunBar("Skill Bill · paused", step, planningSegment, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Stale ->
                buildRunBar("Skill Bill · stale", step, planningSegment, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Done ->
                buildRunBar("Skill Bill · done", step, null, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Blocked -> "Skill Bill · blocked"
            is SkillBillStatusUiState.Failed -> "Skill Bill · failed"
            is SkillBillStatusUiState.Unavailable -> "Skill Bill · unavailable"
            is SkillBillStatusUiState.Incompatible -> "Skill Bill · incompatible"
            is SkillBillStatusUiState.Idle -> "Skill Bill · idle"
        }

        val barText = truncateForBar(normalizeLabel(fullBar) ?: fullBar)
        val preplanLine = planning?.let { "Pre-planning: " + if (it.sharedPreplanPrepared) "Done" else "In progress" }
        val planningLine = planning?.let { "Planning: ${it.plannedSubtaskCount}/${it.totalSubtaskCount} subtasks" }
        val tooltip = buildTooltip(
            anchored,
            lifecycle,
            step,
            goalText,
            subtaskText,
            progressText,
            preplanLine,
            planningLine,
        )
        val accessibleName = "Skill Bill status: $lifecycle"
        val accessibleDescription = buildAccessibilityDescription(
            lifecycle = lifecycle,
            step = step,
            goalText = goalText,
            subtaskText = subtaskText,
            progressText = progressText,
            detail = anchored.detail,
            elapsedNoun = elapsedNoun(anchored),
            preplanLine = preplanLine,
            planningLine = planningLine,
        )

        return MappedPresentation(
            barText = barText,
            tooltipText = tooltip,
            accessibleName = accessibleName,
            accessibleDescription = accessibleDescription,
            showActivityAnimation = anchored is SkillBillStatusUiState.Active,
            isStaleMarked = anchored.stale,
            details = StatusBarDetails(
                issueKey = anchored.issueKey,
                workflowId = anchored.workflowId,
                lifecycleState = lifecycle,
                stepLabel = step ?: anchored.stepLabel,
                modelText = modelText,
                progressText = progressText,
                goalElapsedText = goalText,
                subtaskElapsedText = subtaskText,
                elapsedNoun = elapsedNoun(anchored),
                lastUpdateText = anchored.lastUpdated?.let { lastUpdateFormatter.format(it) },
                problemSummary = anchored.problemSummary ?: anchored.detail,
                staleNote = STALE_NOTE.takeIf { anchored.stale },
            ),
            controls = GoalControlsPresentation.controlsFor(anchored),
        )
    }

    fun validProgress(completed: Int?, total: Int?): Pair<Int, Int>? {
        if (completed == null || total == null) return null
        if (total <= 0 || completed < 0 || completed > total) return null
        return completed to total
    }

    fun normalizeLabel(raw: String?): String? {
        if (raw == null) return null
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch.isISOControl() -> append(' ')
                    else -> append(ch)
                }
            }
        }.trim().replace(Regex("\\s+"), " ")
        return cleaned.takeIf { it.isNotEmpty() }
    }

    fun truncateForBar(text: String, maxLength: Int = BAR_TEXT_MAX_LENGTH): String {
        if (text.length <= maxLength) return text
        if (maxLength <= 1) return "…"
        return text.take(maxLength - 1) + "…"
    }

    fun elapsedLabel(duration: Duration?): String =
        if (duration == null) UNAVAILABLE_ELAPSED else formatDuration(duration)

    /**
     * The rendered numerator is the *current* position, not the completed count: with a
     * subtask in flight "1/4" reads as "1 of 4 done" and contradicts the running subtask
     * clock beside it. Only in-flight lifecycles shift; the wire fields are untouched.
     */
    private fun renderedPosition(state: SkillBillStatusUiState, completed: Int, total: Int): Int {
        val inFlight = state is SkillBillStatusUiState.Active ||
            state is SkillBillStatusUiState.Stale ||
            state is SkillBillStatusUiState.Paused
        return if (inFlight && completed < total) completed + 1 else completed
    }

    /**
     * The bar has a hard 48-char budget, and prefix + step + planning + two clocks +
     * progress overflows it, so a plain concatenation loses whichever segment lands last.
     * Segments are therefore dropped by ascending value until the line fits: the progress
     * pair is redundant while planning is shown (planning only renders before execution
     * starts), and the subtask clock is the least informative of the two clocks.
     */
    private fun buildRunBar(
        prefix: String,
        stepLabel: String?,
        planningSegment: String?,
        goalText: String,
        subtaskText: String,
        progressText: String?,
    ): String {
        val progressRank = if (planningSegment == null) 2 else 0
        val optional = listOf(subtaskText to 1, progressText to progressRank)
        var dropRank = -1
        while (true) {
            val bar = buildString {
                append(prefix)
                stepLabel?.let { append(" · ").append(it) }
                planningSegment?.let { append(" · ").append(it) }
                if (dropRank < 3) append(" · ").append(goalText)
                for ((text, rank) in optional) {
                    if (text != null && rank > dropRank) append(" · ").append(text)
                }
            }
            if (bar.length <= BAR_TEXT_MAX_LENGTH || dropRank >= 3) return bar
            dropRank++
        }
    }

    private fun buildTooltip(
        state: SkillBillStatusUiState,
        lifecycle: String,
        step: String?,
        goalText: String,
        subtaskText: String,
        progressText: String?,
        preplanLine: String?,
        planningLine: String?,
    ): String =
        buildString {
            append("Skill Bill — ").append(lifecycle)
            state.issueKey?.let { append("\nIssue: ").append(it) }
            state.workflowId?.let { append("\nWorkflow: ").append(it) }
            step?.let { append("\nStep: ").append(it) }
            preplanLine?.let { append('\n').append(it) }
            planningLine?.let { append('\n').append(it) }
            val elapsedNoun = elapsedNoun(state)
            append("\nGoal ").append(elapsedNoun).append(": ").append(goalText)
            append("\nSubtask ").append(elapsedNoun).append(": ").append(subtaskText)
            progressText?.let { append("\nProgress: ").append(it) }
            state.lastUpdated?.let { append("\nLast update: ").append(lastUpdateFormatter.format(it)) }
            val problem = state.problemSummary ?: state.detail
            if (!problem.isNullOrBlank()) {
                append("\n").append(problem)
            }
            if (state.stale) {
                append('\n').append(STALE_NOTE)
            }
            if (state is SkillBillStatusUiState.Unavailable) {
                append("\nReason: ").append(state.reasonCode)
            }
            if (state is SkillBillStatusUiState.Incompatible) {
                state.foundContractVersion?.let { append("\nFound contract: ").append(it) }
            }
        }

    private fun buildAccessibilityDescription(
        lifecycle: String,
        step: String?,
        goalText: String,
        subtaskText: String,
        progressText: String?,
        detail: String?,
        elapsedNoun: String,
        preplanLine: String?,
        planningLine: String?,
    ): String =
        buildString {
            append("Skill Bill. State: ").append(lifecycle).append('.')
            step?.let { append(" Step: ").append(it).append('.') }
            preplanLine?.let { append(' ').append(it).append('.') }
            planningLine?.let { append(' ').append(it).append('.') }
            append(" Goal ").append(elapsedNoun).append(": ").append(goalText).append('.')
            append(" Subtask ").append(elapsedNoun).append(": ").append(subtaskText).append('.')
            progressText?.let { append(" Progress: ").append(it).append('.') }
            detail?.let { append(' ').append(it) }
        }

    /**
     * A live run has "elapsed" time; a settled one has a duration it "ran" for. The
     * frozen number must not read as a still-running clock. States that describe no
     * run at all (idle, unavailable, incompatible) keep the neutral noun — "ran"
     * there would assert a past execution that never happened.
     */
    private fun elapsedNoun(state: SkillBillStatusUiState): String =
        when (state) {
            is SkillBillStatusUiState.Stale,
            is SkillBillStatusUiState.Blocked,
            is SkillBillStatusUiState.Failed,
            is SkillBillStatusUiState.Paused,
            is SkillBillStatusUiState.Done,
            -> "ran"

            else -> "elapsed"
        }

    private fun lifecycleLabel(state: SkillBillStatusUiState): String =
        when (state) {
            is SkillBillStatusUiState.Idle -> "idle"
            is SkillBillStatusUiState.Done -> "done"
            is SkillBillStatusUiState.Active -> "active"
            is SkillBillStatusUiState.Paused -> "paused"
            is SkillBillStatusUiState.Stale -> "stale"
            is SkillBillStatusUiState.Blocked -> "blocked"
            is SkillBillStatusUiState.Failed -> "failed"
            is SkillBillStatusUiState.Unavailable -> "unavailable"
            is SkillBillStatusUiState.Incompatible -> "incompatible"
        }

    data class MappedPresentation(
        val barText: String,
        val tooltipText: String,
        val accessibleName: String,
        val accessibleDescription: String,
        val showActivityAnimation: Boolean,
        val isStaleMarked: Boolean,
        val details: StatusBarDetails,
        /** Already-mapped goal controls; renderers consume these and decide nothing. */
        val controls: List<GoalControlDescriptor> = emptyList(),
    )

    data class StatusBarDetails(
        val issueKey: String?,
        val workflowId: String?,
        val lifecycleState: String,
        val stepLabel: String?,
        /** Pre-resolved model row for the details popup; null renders no row at all. */
        val modelText: String? = null,
        val progressText: String?,
        val goalElapsedText: String,
        val subtaskElapsedText: String,
        /** "elapsed" while live, "ran" once settled — the duration stops advancing. */
        val elapsedNoun: String,
        val lastUpdateText: String?,
        val problemSummary: String?,
        /** Caveat for surfaces that render these numbers; null when the reading is live. */
        val staleNote: String?,
    )
}
