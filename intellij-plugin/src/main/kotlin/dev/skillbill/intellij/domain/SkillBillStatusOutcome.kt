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

    /**
     * A terminal lifecycle with a finished run to show. Distinct from [Idle] because a
     * completed goal keeps its issue key and final progress on screen for the runtime's
     * settled-retention window instead of blanking to an empty repository.
     */
    data class Done(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val progressCompleted: Int?,
        val progressTotal: Int?,
        val startedAt: Instant?,
        val updatedAt: Instant?,
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
        val planning: GoalPlanningInfo? = null,
        /**
         * A pause is requested but not yet consumed at a boundary. Optional on the wire:
         * null means the snapshot said nothing, which is not the same as an explicit false.
         */
        val pauseRequested: Boolean? = null,
        /** Instant a recorded pause took effect; absent for an inferred pause. */
        val pausedAt: Instant? = null,
        /**
         * Execution time the runtime accumulated for this goal, excluding blocked, paused, and
         * unattended gaps. Null when the snapshot carried none, which is not zero work.
         */
        val activeDurationMs: Long? = null,
        /** Instant [activeDurationMs] is current as of; present only while a lease is live. */
        val activeDurationAsOf: Instant? = null,
    ) : SkillBillStatusOutcome()

    /**
     * A live lifecycle that is not running. Distinct from [Active] because spinner and
     * ticking-clock decisions key off the type: a paused goal must show neither.
     */
    data class Paused(
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
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant,
        override val diagnostic: StatusDiagnostic? = null,
        val planning: GoalPlanningInfo? = null,
        /** See [Active.pauseRequested]; null means the snapshot said nothing. */
        val pauseRequested: Boolean? = null,
        /** Instant a recorded pause took effect; absent for an inferred pause. */
        val pausedAt: Instant? = null,
        /** See [Active.activeDurationMs]. A paused goal accumulates none while it waits. */
        val activeDurationMs: Long? = null,
        /** See [Active.activeDurationAsOf]; absent once the runner released its lease. */
        val activeDurationAsOf: Instant? = null,
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
        val planning: GoalPlanningInfo? = null,
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

/**
 * True only for an [SkillBillStatusOutcome.Idle] derived from the `no_matching_work`
 * problem code — an idle reading the runtime has not corroborated with a settled
 * lifecycle. Lifecycle-derived idles carry no diagnostic and are never uncorroborated.
 */
fun SkillBillStatusOutcome.isUncorroboratedIdle(): Boolean =
    this is SkillBillStatusOutcome.Idle && diagnostic?.reasonCode == NO_MATCHING_WORK_REASON_CODE

/**
 * A lifecycle the runtime is actively tracking. Distinct from settled or absent
 * readings because only a live display is worth holding across an unconfirmed idle.
 */
fun SkillBillStatusOutcome.isLiveOutcome(): Boolean = when (this) {
    is SkillBillStatusOutcome.Active,
    is SkillBillStatusOutcome.Paused,
    is SkillBillStatusOutcome.Blocked,
    is SkillBillStatusOutcome.Failed,
    is SkillBillStatusOutcome.Stale,
    -> true

    is SkillBillStatusOutcome.Idle,
    is SkillBillStatusOutcome.Done,
    is SkillBillStatusOutcome.Unavailable,
    is SkillBillStatusOutcome.Incompatible,
    -> false
}

/**
 * Goal planning progress carried alongside a live goal snapshot. [state] holds the raw
 * wire value; the plugin never restates the runtime's planning-state vocabulary.
 */
data class GoalPlanningInfo(
    val state: String,
    val sharedPreplanPrepared: Boolean,
    val plannedSubtaskCount: Int,
    val totalSubtaskCount: Int,
    val currentPlanningSubtaskId: String? = null,
    val reason: String? = null,
)

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
