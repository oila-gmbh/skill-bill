package dev.skillbill.intellij.domain

import java.time.Instant

/**
 * Last-known display snapshot suitable for preference persistence.
 * Observation time is required whenever a cached snapshot exists.
 * Consumers may surface this only as [SkillBillStatusOutcome.Stale], never as
 * an authoritative active state.
 */
data class LastKnownDisplayCache(
    val display: CachedDisplaySnapshot,
    val observedAt: Instant,
)

/**
 * Safe, bounded fields for stale UI fallback. No tokens, prompts, stderr,
 * absolute paths, or unbounded blobs.
 */
data class CachedDisplaySnapshot(
    val summary: String,
    val repositoryIdentity: String? = null,
    val issueKey: String? = null,
    val currentStepId: String? = null,
    val currentStepLabel: String? = null,
    val progressCompleted: Int? = null,
    val progressTotal: Int? = null,
    val startedAt: Instant? = null,
    val currentSubtaskId: String? = null,
    val subtaskStartedAt: Instant? = null,
    val updatedAt: Instant? = null,
    /**
     * Accumulated execution time, so a cached fallback keeps reporting the runtime's clock
     * instead of reverting to wall clock since [startedAt]. The live anchor is deliberately
     * not cached: a cache fallback is never live, so the value it restores is final.
     */
    val activeDurationMs: Long? = null,
) {
    init {
        require(summary.isNotBlank()) { "summary must not be blank" }
        require(summary.length <= MAX_SUMMARY_CHARS) { "summary exceeds bound" }
    }

    companion object {
        const val MAX_SUMMARY_CHARS: Int = 512
    }
}

fun SkillBillStatusOutcome.toCacheSnapshotOrNull(): LastKnownDisplayCache? =
    when (this) {
        is SkillBillStatusOutcome.Active ->
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = summary,
                    repositoryIdentity = repositoryIdentity,
                    issueKey = issueKey,
                    currentStepId = currentStepId,
                    currentStepLabel = currentStepLabel,
                    progressCompleted = progressCompleted,
                    progressTotal = progressTotal,
                    startedAt = startedAt,
                    currentSubtaskId = currentSubtaskId,
                    subtaskStartedAt = subtaskStartedAt,
                    updatedAt = updatedAt,
                    activeDurationMs = activeDurationMs,
                ),
                observedAt = observedAt,
            )

        is SkillBillStatusOutcome.Paused ->
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = summary,
                    repositoryIdentity = repositoryIdentity,
                    issueKey = issueKey,
                    currentStepId = currentStepId,
                    currentStepLabel = currentStepLabel,
                    progressCompleted = progressCompleted,
                    progressTotal = progressTotal,
                    startedAt = startedAt,
                    currentSubtaskId = currentSubtaskId,
                    subtaskStartedAt = subtaskStartedAt,
                    updatedAt = updatedAt,
                    activeDurationMs = activeDurationMs,
                ),
                observedAt = observedAt,
            )

        is SkillBillStatusOutcome.Stale ->
            if (fromCache) {
                null
            } else {
                LastKnownDisplayCache(
                    display = CachedDisplaySnapshot(
                        summary = summary,
                        repositoryIdentity = repositoryIdentity,
                        issueKey = issueKey,
                        currentStepId = currentStepId,
                        currentStepLabel = currentStepLabel,
                        progressCompleted = progressCompleted,
                        progressTotal = progressTotal,
                        startedAt = startedAt,
                        currentSubtaskId = currentSubtaskId,
                        subtaskStartedAt = subtaskStartedAt,
                        updatedAt = updatedAt,
                        activeDurationMs = activeDurationMs,
                    ),
                    observedAt = observedAt,
                )
            }

        is SkillBillStatusOutcome.Blocked ->
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = summary,
                    repositoryIdentity = repositoryIdentity,
                    issueKey = issueKey,
                    currentStepId = currentStepId,
                    currentStepLabel = currentStepLabel,
                    startedAt = startedAt,
                    currentSubtaskId = currentSubtaskId,
                    subtaskStartedAt = subtaskStartedAt,
                    updatedAt = updatedAt,
                    activeDurationMs = activeDurationMs,
                ),
                observedAt = observedAt,
            )

        is SkillBillStatusOutcome.Failed ->
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = summary,
                    repositoryIdentity = repositoryIdentity,
                    issueKey = issueKey,
                    currentStepId = currentStepId,
                    currentStepLabel = currentStepLabel,
                    startedAt = startedAt,
                    currentSubtaskId = currentSubtaskId,
                    subtaskStartedAt = subtaskStartedAt,
                    updatedAt = updatedAt,
                    activeDurationMs = activeDurationMs,
                ),
                observedAt = observedAt,
            )

        is SkillBillStatusOutcome.Done ->
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = summary,
                    repositoryIdentity = repositoryIdentity,
                    issueKey = issueKey,
                    progressCompleted = progressCompleted,
                    progressTotal = progressTotal,
                    startedAt = startedAt,
                    updatedAt = updatedAt,
                    activeDurationMs = activeDurationMs,
                ),
                observedAt = observedAt,
            )

        is SkillBillStatusOutcome.Idle,
        is SkillBillStatusOutcome.Unavailable,
        is SkillBillStatusOutcome.Incompatible,
        -> null
    }

fun LastKnownDisplayCache.toStaleOutcome(): SkillBillStatusOutcome.Stale =
    SkillBillStatusOutcome.Stale(
        observedAt = observedAt,
        summary = display.summary,
        repositoryIdentity = display.repositoryIdentity,
        issueKey = display.issueKey,
        currentStepId = display.currentStepId,
        currentStepLabel = display.currentStepLabel,
        progressCompleted = display.progressCompleted,
        progressTotal = display.progressTotal,
        startedAt = display.startedAt,
        currentSubtaskId = display.currentSubtaskId,
        subtaskStartedAt = display.subtaskStartedAt,
        updatedAt = display.updatedAt,
        activeDurationMs = display.activeDurationMs,
        fromCache = true,
        diagnostic = StatusDiagnostic(reasonCode = "cache_fallback"),
    )
