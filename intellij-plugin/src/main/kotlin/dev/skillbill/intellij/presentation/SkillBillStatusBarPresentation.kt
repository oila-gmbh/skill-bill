package dev.skillbill.intellij.presentation

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

    private val lastUpdateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun map(state: SkillBillStatusUiState, now: Instant? = null): MappedPresentation {
        val anchored = if (now == null) state else StatusUiMapper.withElapsed(state, now)
        val lifecycle = lifecycleLabel(anchored)
        val step = normalizeLabel(anchored.stepLabel)
        val goalText = elapsedLabel(anchored.goalElapsed)
        val subtaskText = elapsedLabel(anchored.subtaskElapsed)
        val progress = validProgress(anchored.progressCompleted, anchored.progressTotal)
        val progressText = progress?.let { "${it.first}/${it.second}" }

        val fullBar = when (anchored) {
            is SkillBillStatusUiState.Active ->
                buildActiveBar(step ?: anchored.stepLabel, goalText, subtaskText, progressText)

            is SkillBillStatusUiState.Stale ->
                buildString {
                    append("Skill Bill · stale")
                    step?.let { append(" · ").append(it) }
                    append(" · ").append(goalText)
                    append(" · ").append(subtaskText)
                    progressText?.let { append(" · ").append(it) }
                }

            is SkillBillStatusUiState.Blocked -> "Skill Bill · blocked"
            is SkillBillStatusUiState.Failed -> "Skill Bill · failed"
            is SkillBillStatusUiState.Unavailable -> "Skill Bill · unavailable"
            is SkillBillStatusUiState.Incompatible -> "Skill Bill · incompatible"
            is SkillBillStatusUiState.Idle -> "Skill Bill · idle"
        }

        val barText = truncateForBar(normalizeLabel(fullBar) ?: fullBar)
        val tooltip = buildTooltip(anchored, lifecycle, step, goalText, subtaskText, progressText)
        val accessibleName = "Skill Bill status: $lifecycle"
        val accessibleDescription = buildAccessibilityDescription(
            lifecycle = lifecycle,
            step = step,
            goalText = goalText,
            subtaskText = subtaskText,
            progressText = progressText,
            detail = anchored.detail,
        )

        return MappedPresentation(
            barText = barText,
            tooltipText = tooltip,
            accessibleName = accessibleName,
            accessibleDescription = accessibleDescription,
            showActivityAnimation = anchored is SkillBillStatusUiState.Active,
            isStaleMarked = anchored is SkillBillStatusUiState.Stale,
            details = StatusBarDetails(
                issueKey = anchored.issueKey,
                workflowId = anchored.workflowId,
                lifecycleState = lifecycle,
                stepLabel = step ?: anchored.stepLabel,
                progressText = progressText,
                goalElapsedText = goalText,
                subtaskElapsedText = subtaskText,
                lastUpdateText = anchored.lastUpdated?.let { lastUpdateFormatter.format(it) },
                problemSummary = anchored.problemSummary ?: anchored.detail,
            ),
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

    private fun buildActiveBar(
        stepLabel: String,
        goalText: String,
        subtaskText: String,
        progressText: String?,
    ): String =
        buildString {
            append("Skill Bill · ").append(stepLabel)
            append(" · ").append(goalText)
            append(" · ").append(subtaskText)
            progressText?.let { append(" · ").append(it) }
        }

    private fun buildTooltip(
        state: SkillBillStatusUiState,
        lifecycle: String,
        step: String?,
        goalText: String,
        subtaskText: String,
        progressText: String?,
    ): String =
        buildString {
            append("Skill Bill — ").append(lifecycle)
            state.issueKey?.let { append("\nIssue: ").append(it) }
            state.workflowId?.let { append("\nWorkflow: ").append(it) }
            step?.let { append("\nStep: ").append(it) }
            append("\nGoal elapsed: ").append(goalText)
            append("\nSubtask elapsed: ").append(subtaskText)
            progressText?.let { append("\nProgress: ").append(it) }
            state.lastUpdated?.let { append("\nLast update: ").append(lastUpdateFormatter.format(it)) }
            val problem = state.problemSummary ?: state.detail
            if (!problem.isNullOrBlank()) {
                append("\n").append(problem)
            }
            if (state is SkillBillStatusUiState.Stale) {
                append("\n(Stale — not live)")
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
    ): String =
        buildString {
            append("Skill Bill. State: ").append(lifecycle).append('.')
            step?.let { append(" Step: ").append(it).append('.') }
            append(" Goal elapsed: ").append(goalText).append('.')
            append(" Subtask elapsed: ").append(subtaskText).append('.')
            progressText?.let { append(" Progress: ").append(it).append('.') }
            detail?.let { append(' ').append(it) }
        }

    private fun lifecycleLabel(state: SkillBillStatusUiState): String =
        when (state) {
            is SkillBillStatusUiState.Idle -> "idle"
            is SkillBillStatusUiState.Active -> "active"
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
    )

    data class StatusBarDetails(
        val issueKey: String?,
        val workflowId: String?,
        val lifecycleState: String,
        val stepLabel: String?,
        val progressText: String?,
        val goalElapsedText: String,
        val subtaskElapsedText: String,
        val lastUpdateText: String?,
        val problemSummary: String?,
    )
}
