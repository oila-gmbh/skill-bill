package dev.skillbill.intellij.domain

import java.time.Instant

/**
 * Domain outcomes for Skill Bill IDE status. Distinct from wire lifecycle enums:
 * freshness, problem codes, and transport failures collapse into these UI-facing
 * outcomes so presentation can map exhaustively without transport knowledge.
 */
sealed class SkillBillStatusOutcome {
    abstract val observedAt: Instant
    abstract val diagnostic: StatusDiagnostic?

    data class Idle(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String? = null,
        /** Freshness modifies a settled lifecycle; it never replaces it. */
        val stale: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    data class Active(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String,
        val issueKey: String?,
        val workflowId: String?,
        val workflowFamily: String?,
        val currentStepId: String,
        val currentStepLabel: String,
        val progressCompleted: Int?,
        val progressTotal: Int?,
        /** Authoritative goal/work start; never synthesized from updated_at. */
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        /** Authoritative subtask start; omitted when absent on the wire. */
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    data class Stale(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val currentStepId: String?,
        val currentStepLabel: String?,
        val progressCompleted: Int?,
        val progressTotal: Int?,
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant?,
        val fromCache: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    data class Blocked(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val currentStepId: String?,
        val currentStepLabel: String?,
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant?,
        /** Freshness modifies a settled lifecycle; it never replaces it. */
        val stale: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    data class Failed(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val currentStepId: String?,
        val currentStepLabel: String?,
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant?,
        /** Freshness modifies a settled lifecycle; it never replaces it. */
        val stale: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    data class Unavailable(
        override val observedAt: Instant,
        val summary: String,
        val reasonCode: UnavailableReason,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    data class Incompatible(
        override val observedAt: Instant,
        val summary: String,
        val foundContractVersion: String?,
        val expectedContractVersion: String = IDE_STATUS_CONTRACT_VERSION,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()
}

enum class UnavailableReason {
    MISSING_EXECUTABLE,
    MISCONFIGURED,
    MISSING_REPOSITORY,
    ABSENT_DATABASE,
    /**
     * Retained for wire compatibility only. The `no_matching_work` contract code maps
     * to [SkillBillStatusOutcome.Idle] — an empty repository is idle, not unavailable.
     */
    NO_MATCHING_WORK,
    INVALID_REPOSITORY_INPUT,
    PROCESS_FAILURE,
    TIMEOUT,
    CANCELLED,
    MALFORMED_OUTPUT,
}

/**
 * Typed troubleshooting context only — never raw stderr, tokens, prompts,
 * phase artifacts, or absolute sensitive paths.
 */
data class StatusDiagnostic(
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val contractVersionMismatch: Boolean = false,
    val foundContractVersion: String? = null,
    val reasonCode: String? = null,
)
