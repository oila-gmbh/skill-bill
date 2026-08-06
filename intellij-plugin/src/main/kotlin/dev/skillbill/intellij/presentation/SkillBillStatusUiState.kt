package dev.skillbill.intellij.presentation

import java.time.Duration

/**
 * Immutable presentation state for Skill Bill status surfaces.
 * No process, JSON, filesystem, or status-bar rendering code here.
 */
sealed class SkillBillStatusUiState {
    abstract val headline: String
    abstract val detail: String?
    abstract val goalElapsed: Duration?
    abstract val subtaskElapsed: Duration?
    abstract val progressCompleted: Int?
    abstract val progressTotal: Int?
    abstract val accessibilityText: String

    data class Idle(
        override val headline: String = "Skill Bill: idle",
        override val detail: String? = null,
        override val goalElapsed: Duration? = null,
        override val subtaskElapsed: Duration? = null,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = headline
    }

    data class Active(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int?,
        override val progressTotal: Int?,
        val issueKey: String?,
        val stepLabel: String,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String =
            buildString {
                append(headline)
                goalElapsed?.let { append(", goal elapsed ").append(formatDuration(it)) }
                subtaskElapsed?.let { append(", subtask elapsed ").append(formatDuration(it)) }
            }
    }

    data class Stale(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int?,
        override val progressTotal: Int?,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (stale)"
    }

    data class Blocked(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (blocked)"
    }

    data class Failed(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (failed)"
    }

    data class Unavailable(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration? = null,
        override val subtaskElapsed: Duration? = null,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        val reasonCode: String,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (unavailable)"
    }

    data class Incompatible(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration? = null,
        override val subtaskElapsed: Duration? = null,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        val foundContractVersion: String?,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (incompatible)"
    }
}

internal fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.seconds.coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(hours, minutes)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
