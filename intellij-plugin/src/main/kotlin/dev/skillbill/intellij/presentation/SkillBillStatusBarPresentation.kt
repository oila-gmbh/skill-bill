package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.CurrentPhaseExecution
import dev.skillbill.intellij.domain.FEATURE_GOAL_WORKFLOW_FAMILY
import dev.skillbill.intellij.domain.GOAL_FINDINGS_DISPLAY_COMMAND
import dev.skillbill.intellij.domain.GoalPlanningInfo
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

    const val PAUSE_DECISION_ACTION_NOTE: String =
        "Waiting on your decision — resolve with: $GOAL_FINDINGS_DISPLAY_COMMAND <KEY>"

    private val lastUpdateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun map(state: SkillBillStatusUiState, now: Instant? = null): MappedPresentation {
        val anchored = if (now == null) state else StatusUiMapper.withElapsed(state, now)
        val lifecycle = lifecycleLabel(anchored)
        val step = normalizeLabel(anchored.stepLabel)
        val goalText = elapsedLabel(anchored.goalElapsed)
        val subtaskText = elapsedLabel(anchored.subtaskElapsed)
        val selectedSlot = selectDisplaySlot(anchored.planning, anchored.currentPhaseExecution)
        val progress = when {
            selectedSlot is DisplaySlot.Planning ->
                selectedSlot.planned to selectedSlot.total
            else ->
                validProgress(anchored.progressCompleted, anchored.progressTotal)
        }
        val progressText = progress?.let { (completed, total) ->
            when (selectedSlot) {
                is DisplaySlot.Planning -> "$completed/$total"
                else -> "${renderedPosition(anchored, completed, total)}/$total"
            }
        }
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
        val slotSegment = selectedSlot?.barSegment

        val fullBar = when (anchored) {
            is SkillBillStatusUiState.Active ->
                buildRunBar("Skill Bill", step ?: anchored.stepLabel, slotSegment, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Paused ->
                buildRunBar("Skill Bill · paused", step, slotSegment, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Stale ->
                buildRunBar("Skill Bill · stale", step, slotSegment, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Done ->
                buildRunBar("Skill Bill · done", step, null, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Blocked -> "Skill Bill · blocked"
            is SkillBillStatusUiState.Failed -> "Skill Bill · failed"
            is SkillBillStatusUiState.Unavailable -> "Skill Bill · unavailable"
            is SkillBillStatusUiState.Incompatible -> "Skill Bill · incompatible"
            is SkillBillStatusUiState.Idle -> "Skill Bill · idle"
        }

        val barText = truncateForBar(normalizeLabel(fullBar) ?: fullBar)
        val slotFullLine = selectedSlot?.fullLine
        val tooltip = buildTooltip(
            anchored,
            lifecycle,
            step,
            goalText,
            subtaskText,
            progressText,
            slotFullLine,
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
            slotFullLine = slotFullLine,
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
                selectedSlotLabel = selectedSlot?.popupLabel,
                selectedSlotText = selectedSlot?.popupValue,
                progressText = progressText,
                goalElapsedText = goalText,
                subtaskElapsedText = subtaskText,
                elapsedNoun = elapsedNoun(anchored),
                lastUpdateText = anchored.lastUpdated?.let { lastUpdateFormatter.format(it) },
                agentActivityText = agentActivityText(anchored),
                problemSummary = anchored.problemSummary ?: anchored.detail,
                staleNote = STALE_NOTE.takeIf { anchored.stale },
                pauseReasonText = pauseReasonText(anchored),
                pauseActionText = pauseActionText(anchored),
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

    internal fun agentActivityText(state: SkillBillStatusUiState): String? {
        val label: String?
        val at: Instant?
        when (state) {
            is SkillBillStatusUiState.Active -> {
                label = state.lastAgentActivityLabel
                at = state.lastAgentActivityAt
            }
            is SkillBillStatusUiState.Paused -> {
                label = state.lastAgentActivityLabel
                at = state.lastAgentActivityAt
            }
            is SkillBillStatusUiState.Stale -> {
                label = state.lastAgentActivityLabel
                at = state.lastAgentActivityAt
            }
            else -> return null
        }
        if (label.isNullOrBlank() || at == null) return null
        return "$label | ${lastUpdateFormatter.format(at)}"
    }

    fun elapsedLabel(duration: Duration?): String =
        if (duration == null) UNAVAILABLE_ELAPSED else formatDuration(duration)

    /**
     * One selected planning-or-current-execution slot so bar, tooltip, accessibility, and popup
     * cannot disagree. Planning wins while still relevant; once it is gone the producer-supplied
     * execution value fills the same slot. Neither value synthesizes the other.
     */
    internal fun selectDisplaySlot(
        planning: GoalPlanningInfo?,
        execution: CurrentPhaseExecution?,
    ): DisplaySlot? {
        if (planning != null) {
            val planned = planning.plannedSubtaskCount
            val total = planning.totalSubtaskCount
            val stateLabel = planningStateLabel(planning.state)
            return DisplaySlot.Planning(
                planned = planned,
                total = total,
                barSegment = "Planning $planned/$total",
                fullLine = "Planning: $stateLabel, $planned/$total plans saved",
                popupLabel = "Planning",
                popupValue = "$stateLabel, $planned/$total plans saved",
            )
        }
        if (execution != null) {
            val wording = executionWording(execution)
            return DisplaySlot.Execution(
                barSegment = wording,
                fullLine = wording,
                popupLabel = "Current phase",
                popupValue = wording,
            )
        }
        return null
    }

    /**
     * The rendered numerator is the *current* position, not the completed count: with a
     * subtask in flight "1/4" reads as "1 of 4 done" and contradicts the running subtask
     * clock beside it. Only in-flight lifecycles shift; the wire fields are untouched.
     * Planning progress never uses this offset — it comes from planned/total counts directly.
     */
    private fun renderedPosition(state: SkillBillStatusUiState, completed: Int, total: Int): Int {
        val inFlight = state is SkillBillStatusUiState.Active ||
            state is SkillBillStatusUiState.Stale ||
            state is SkillBillStatusUiState.Paused
        return if (inFlight && completed < total) completed + 1 else completed
    }

    /**
     * The bar has a hard 48-char budget, and prefix + step + planning/execution + two clocks +
     * progress overflows it, so a plain concatenation loses whichever segment lands last.
     * Segments are therefore dropped by ascending value until the line fits: the progress
     * pair is redundant while the selected slot is shown, and the subtask clock is the least
     * informative of the two clocks.
     */
    private fun buildRunBar(
        prefix: String,
        stepLabel: String?,
        slotSegment: String?,
        goalText: String,
        subtaskText: String,
        progressText: String?,
    ): String {
        val progressRank = if (slotSegment == null) 2 else 0
        val optional = listOf(subtaskText to 1, progressText to progressRank)
        var dropRank = -1
        while (true) {
            val bar = buildString {
                append(prefix)
                stepLabel?.let { append(" · ").append(it) }
                slotSegment?.let { append(" · ").append(it) }
                if (dropRank < 3) append(" · ").append(goalText)
                for ((text, rank) in optional) {
                    if (text != null && rank > dropRank) append(" · ").append(text)
                }
            }
            if (bar.length <= BAR_TEXT_MAX_LENGTH || dropRank >= 3) return bar
            dropRank++
        }
    }

    private fun pauseReasonText(state: SkillBillStatusUiState): String? = state.pauseReason?.let { reason ->
        reason.label ?: reason.code.replace('_', ' ')
    }

    private fun pauseActionText(state: SkillBillStatusUiState): String? = state.pauseReason
        ?.takeIf { it.awaitsOperatorDecision }
        ?.let { PAUSE_DECISION_ACTION_NOTE }

    private fun buildTooltip(
        state: SkillBillStatusUiState,
        lifecycle: String,
        step: String?,
        goalText: String,
        subtaskText: String,
        progressText: String?,
        slotFullLine: String?,
    ): String =
        buildString {
            append("Skill Bill — ").append(lifecycle)
            state.issueKey?.let { append("\nIssue: ").append(it) }
            state.workflowId?.let { append("\nWorkflow: ").append(it) }
            step?.let { append("\nStep: ").append(it) }
            slotFullLine?.let { append('\n').append(it) }
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
            pauseReasonText(state)?.let { append("\nPause reason: ").append(it) }
            pauseActionText(state)?.let { append('\n').append(it) }
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
        slotFullLine: String?,
    ): String =
        buildString {
            append("Skill Bill. State: ").append(lifecycle).append('.')
            step?.let { append(" Step: ").append(it).append('.') }
            slotFullLine?.let { append(' ').append(it).append('.') }
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
        /**
         * Exactly one planning or current-phase execution row when relevant. Label and value
         * arrive pre-resolved so the popup stays passive; both null renders no such row.
         */
        val selectedSlotLabel: String? = null,
        val selectedSlotText: String? = null,
        val progressText: String?,
        val goalElapsedText: String,
        val subtaskElapsedText: String,
        /** "elapsed" while live, "ran" once settled — the duration stops advancing. */
        val elapsedNoun: String,
        val lastUpdateText: String?,
        val agentActivityText: String? = null,
        val problemSummary: String?,
        /** Caveat for surfaces that render these numbers; null when the reading is live. */
        val staleNote: String?,
        val pauseReasonText: String? = null,
        val pauseActionText: String? = null,
    )

    /**
     * Shared formatter seam for the single planning-or-execution display slot. Compact and full
     * surfaces read from the same instance so they cannot disagree about which value is shown.
     * Strings are computed by [selectDisplaySlot] so nested classes never call enclosing members.
     */
    internal sealed class DisplaySlot {
        abstract val barSegment: String
        abstract val fullLine: String
        abstract val popupLabel: String
        abstract val popupValue: String

        data class Planning(
            val planned: Int,
            val total: Int,
            override val barSegment: String,
            override val fullLine: String,
            override val popupLabel: String,
            override val popupValue: String,
        ) : DisplaySlot()

        data class Execution(
            override val barSegment: String,
            override val fullLine: String,
            override val popupLabel: String,
            override val popupValue: String,
        ) : DisplaySlot()
    }

    private fun planningStateLabel(state: String): String =
        when (state) {
            "not_started" -> "not started"
            "preplanned" -> "preplanned"
            "partially_planned" -> "partially planned"
            "blocked" -> "blocked"
            "prepared" -> "prepared"
            else -> state.replace('_', ' ')
        }

    /**
     * Honest phase-specific wording from producer-supplied kind and count. A total appears only
     * when the producer supplied one (bounded_edge); attempt and gate counts never invent a loop
     * total.
     */
    private fun executionWording(execution: CurrentPhaseExecution): String {
        val phase = phaseDisplayName(execution.phaseId)
        return when (execution.kind) {
            "semantic_loop" -> "$phase loop ${execution.count}"
            "pass" -> "$phase pass ${execution.count}"
            "gate_run" -> "$phase gate ${execution.count}"
            "attempt" -> "$phase attempt ${execution.count}"
            "bounded_edge" -> {
                val total = execution.total
                if (total != null) "$phase ${execution.count}/$total" else "$phase ${execution.count}"
            }
            else -> "$phase ${execution.count}"
        }
    }

    /** Title-cases a phase id after control-character normalization so UI text stays printable. */
    private fun phaseDisplayName(phaseId: String): String {
        val cleaned = normalizeLabel(phaseId) ?: return "Phase"
        return cleaned.split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            .ifBlank { cleaned }
    }
}
