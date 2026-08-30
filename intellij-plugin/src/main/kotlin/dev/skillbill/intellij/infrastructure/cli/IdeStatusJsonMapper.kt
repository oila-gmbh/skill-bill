package dev.skillbill.intellij.infrastructure.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dev.skillbill.intellij.domain.ACTIVE_DURATION_AS_OF_WIRE_KEY
import dev.skillbill.intellij.domain.AGENT_ACTIVITY_LABELS
import dev.skillbill.intellij.domain.LAST_AGENT_ACTIVITY_AT_WIRE_KEY
import dev.skillbill.intellij.domain.LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY
import dev.skillbill.intellij.domain.ACTIVE_DURATION_MS_WIRE_KEY
import dev.skillbill.intellij.domain.CURRENT_MODEL_WIRE_KEY
import dev.skillbill.intellij.domain.CURRENT_PHASE_EXECUTION_KINDS
import dev.skillbill.intellij.domain.CURRENT_PHASE_EXECUTION_WIRE_KEY
import dev.skillbill.intellij.domain.CurrentPhaseExecution
import dev.skillbill.intellij.domain.CurrentPhaseModel
import dev.skillbill.intellij.domain.EFFORT_MAX_LENGTH
import dev.skillbill.intellij.domain.MODEL_MAX_LENGTH
import dev.skillbill.intellij.domain.PHASE_ID_MAX_LENGTH
import dev.skillbill.intellij.domain.GoalPlanningInfo
import dev.skillbill.intellij.domain.IDE_STATUS_CONTRACT_VERSION
import dev.skillbill.intellij.domain.NO_MATCHING_WORK_REASON_CODE
import dev.skillbill.intellij.domain.PAUSED_AT_WIRE_KEY
import dev.skillbill.intellij.domain.PAUSE_REASON_CODES
import dev.skillbill.intellij.domain.PAUSE_REASON_LABEL_MAX_LENGTH
import dev.skillbill.intellij.domain.PAUSE_REASON_WIRE_KEY
import dev.skillbill.intellij.domain.PauseReason
import dev.skillbill.intellij.domain.PAUSE_REQUESTED_WIRE_KEY
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusDiagnostic
import dev.skillbill.intellij.domain.UnavailableReason
import dev.skillbill.intellij.infrastructure.AbsolutePathGuard
import java.time.Instant

/**
 * Maps schema-shaped IDE status JSON into domain outcomes.
 * Validates contract_version before mapping; never surfaces stderr or absolute
 * sensitive paths from diagnostics.
 */
object IdeStatusJsonMapper {
    fun map(
        stdout: String,
        observedAt: Instant,
        exitCode: Int?,
    ): SkillBillStatusOutcome {
        if (exitCode != null && exitCode != 0) {
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Skill Bill status command failed",
                reasonCode = UnavailableReason.PROCESS_FAILURE,
                diagnostic = StatusDiagnostic(
                    exitCode = exitCode,
                    reasonCode = "non_zero_exit",
                ),
            )
        }
        val root = parseObject(stdout) ?: return malformed(observedAt, "malformed_json")
        val contractVersion = root.getAsString("contract_version")
        if (contractVersion == null) {
            return SkillBillStatusOutcome.Incompatible(
                observedAt = observedAt,
                summary = "IDE status contract version missing",
                foundContractVersion = null,
                diagnostic = StatusDiagnostic(
                    contractVersionMismatch = true,
                    reasonCode = "missing_contract_version",
                ),
            )
        }
        if (contractVersion != IDE_STATUS_CONTRACT_VERSION) {
            return SkillBillStatusOutcome.Incompatible(
                observedAt = observedAt,
                summary = "IDE status contract version incompatible",
                foundContractVersion = contractVersion,
                diagnostic = StatusDiagnostic(
                    contractVersionMismatch = true,
                    foundContractVersion = contractVersion,
                    reasonCode = "contract_version_mismatch",
                ),
            )
        }

        val problem = root.getAsJsonObjectOrNull("problem")
        val problemCode = problem?.getAsString("code")
        if (problemCode == "schema_incompatible") {
            return SkillBillStatusOutcome.Incompatible(
                observedAt = observedAt,
                summary = safeSummary(problem.getAsString("message"), "Schema incompatible"),
                foundContractVersion = problem.getAsJsonObjectOrNull("details")
                    ?.getAsString("found_contract_version"),
                diagnostic = StatusDiagnostic(
                    contractVersionMismatch = true,
                    reasonCode = problemCode,
                ),
            )
        }

        val freshness = root.getAsString("freshness")
        val lifecycle = root.getAsString("lifecycle_state") ?: "idle"
        val summary = safeSummary(root.getAsString("summary"), "Skill Bill status")
        val repositoryIdentity = root.getAsString("repository_identity")
        val issueKey = root.getAsString("issue_key")
        val workflowId = root.getAsString("workflow_id")
        val workflowFamily = root.getAsString("workflow_family")
        val step = root.getAsJsonObjectOrNull("current_step")
        val stepId = step?.getAsString("id")
        val stepLabel = step?.getAsString("label")
        val progress = root.getAsJsonObjectOrNull("progress")
        val progressCompleted = progress?.getAsInt("completed")
        val progressTotal = progress?.getAsInt("total")
        val startedAt = root.getAsInstant("started_at")
        val subtask = root.getAsJsonObjectOrNull("current_subtask")
        val subtaskId = subtask?.getAsString("id")
        val subtaskStartedAt = subtask?.getAsInstant("started_at")
        val subtaskActiveDurationMs = subtask?.getAsNonNegativeLong(ACTIVE_DURATION_MS_WIRE_KEY)
        val subtaskActiveDurationAsOf = subtask?.getAsInstant(ACTIVE_DURATION_AS_OF_WIRE_KEY)
        val updatedAt = root.getAsInstant("updated_at")
        val planning = root.parsePlanning()
        val currentModel = root.parseCurrentModel()
        val currentPhaseExecution = root.parseCurrentPhaseExecution()
        // Both optional and goal-family-only: a missing key stays null, never false.
        val pauseRequested = root.getAsBoolean(PAUSE_REQUESTED_WIRE_KEY)
        val pausedAt = root.getAsInstant(PAUSED_AT_WIRE_KEY)
        val pauseReason = root.parsePauseReason()
        val activeDurationMs = root.getAsNonNegativeLong(ACTIVE_DURATION_MS_WIRE_KEY)
        val activeDurationAsOf = root.getAsInstant(ACTIVE_DURATION_AS_OF_WIRE_KEY)
        val agentActivity = root.parseAgentActivity()

        // "No work here" is a healthy idle repository, not a broken status source.
        // Every other problem code is a genuine failure to obtain status.
        if (problemCode == NO_MATCHING_WORK_REASON_CODE) {
            return SkillBillStatusOutcome.Idle(
                observedAt = observedAt,
                summary = safeSummary(problem?.getAsString("message"), summary),
                repositoryIdentity = repositoryIdentity,
                diagnostic = StatusDiagnostic(reasonCode = NO_MATCHING_WORK_REASON_CODE),
            )
        }

        if (problemCode != null) {
            val unavailable = when (problemCode) {
                "missing_repository_identity" -> UnavailableReason.MISSING_REPOSITORY
                "absent_database" -> UnavailableReason.ABSENT_DATABASE
                "no_matching_work" -> UnavailableReason.NO_MATCHING_WORK
                "invalid_repository_input" -> UnavailableReason.INVALID_REPOSITORY_INPUT
                "incompatible_record" -> UnavailableReason.MISCONFIGURED
                else -> UnavailableReason.MISCONFIGURED
            }
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = safeSummary(problem?.getAsString("message"), summary),
                reasonCode = unavailable,
                diagnostic = StatusDiagnostic(reasonCode = problemCode),
            )
        }

        val isStale = freshness == "stale"

        // Staleness only *replaces* a lifecycle that claims to be live. A settled
        // lifecycle (blocked/failed/terminal/idle) is the more specific truth and
        // outranks it; there it degrades to a modifier on the lifecycle outcome.
        if (isStale && (lifecycle == "active" || lifecycle == "paused")) {
            return SkillBillStatusOutcome.Stale(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                currentStepId = stepId,
                currentStepLabel = stepLabel,
                progressCompleted = progressCompleted,
                progressTotal = progressTotal,
                startedAt = startedAt,
                currentSubtaskId = subtaskId,
                subtaskStartedAt = subtaskStartedAt,
                updatedAt = updatedAt,
                fromCache = false,
                planning = planning,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
                subtaskActiveDurationMs = subtaskActiveDurationMs,
                subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                currentModel = currentModel,
                currentPhaseExecution = currentPhaseExecution,
                lastAgentActivityAt = agentActivity?.first,
                lastAgentActivityLabel = agentActivity?.second,
            )
        }

        return when (lifecycle) {
            "active", "paused" -> {
                if (repositoryIdentity.isNullOrBlank() || stepId.isNullOrBlank() || stepLabel.isNullOrBlank() || updatedAt == null) {
                    malformed(observedAt, "incomplete_active_payload")
                } else if (lifecycle == "paused") {
                    SkillBillStatusOutcome.Paused(
                        observedAt = observedAt,
                        summary = summary,
                        repositoryIdentity = repositoryIdentity,
                        issueKey = issueKey,
                        workflowId = workflowId,
                        workflowFamily = workflowFamily,
                        currentStepId = stepId,
                        currentStepLabel = stepLabel,
                        progressCompleted = progressCompleted,
                        progressTotal = progressTotal,
                        startedAt = startedAt,
                        currentSubtaskId = subtaskId,
                        subtaskStartedAt = subtaskStartedAt,
                        updatedAt = updatedAt,
                        planning = planning,
                        pauseRequested = pauseRequested,
                        pausedAt = pausedAt,
                        activeDurationMs = activeDurationMs,
                        activeDurationAsOf = activeDurationAsOf,
                        subtaskActiveDurationMs = subtaskActiveDurationMs,
                        subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                        currentModel = currentModel,
                        currentPhaseExecution = currentPhaseExecution,
                        pauseReason = pauseReason,
                        lastAgentActivityAt = agentActivity?.first,
                        lastAgentActivityLabel = agentActivity?.second,
                    )
                } else {
                    SkillBillStatusOutcome.Active(
                        observedAt = observedAt,
                        summary = summary,
                        repositoryIdentity = repositoryIdentity,
                        issueKey = issueKey,
                        workflowId = workflowId,
                        workflowFamily = workflowFamily,
                        currentStepId = stepId,
                        currentStepLabel = stepLabel,
                        progressCompleted = progressCompleted,
                        progressTotal = progressTotal,
                        startedAt = startedAt,
                        currentSubtaskId = subtaskId,
                        subtaskStartedAt = subtaskStartedAt,
                        updatedAt = updatedAt,
                        planning = planning,
                        pauseRequested = pauseRequested,
                        pausedAt = pausedAt,
                        activeDurationMs = activeDurationMs,
                        activeDurationAsOf = activeDurationAsOf,
                        subtaskActiveDurationMs = subtaskActiveDurationMs,
                        subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                        currentModel = currentModel,
                        currentPhaseExecution = currentPhaseExecution,
                        lastAgentActivityAt = agentActivity?.first,
                        lastAgentActivityLabel = agentActivity?.second,
                    )
                }
            }

            "blocked" -> SkillBillStatusOutcome.Blocked(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                currentStepId = stepId,
                currentStepLabel = stepLabel,
                startedAt = startedAt,
                currentSubtaskId = subtaskId,
                subtaskStartedAt = subtaskStartedAt,
                updatedAt = updatedAt,
                stale = isStale,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
                subtaskActiveDurationMs = subtaskActiveDurationMs,
                subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                currentModel = currentModel,
                currentPhaseExecution = currentPhaseExecution,
            )

            "failed" -> SkillBillStatusOutcome.Failed(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                currentStepId = stepId,
                currentStepLabel = stepLabel,
                startedAt = startedAt,
                currentSubtaskId = subtaskId,
                subtaskStartedAt = subtaskStartedAt,
                updatedAt = updatedAt,
                stale = isStale,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
                subtaskActiveDurationMs = subtaskActiveDurationMs,
                subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                currentModel = currentModel,
                currentPhaseExecution = currentPhaseExecution,
            )

            "idle" -> SkillBillStatusOutcome.Idle(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                stale = isStale,
            )

            "terminal" -> SkillBillStatusOutcome.Done(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                progressCompleted = progressCompleted,
                progressTotal = progressTotal,
                startedAt = startedAt,
                updatedAt = updatedAt,
                stale = isStale,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
            )

            else -> SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Unknown lifecycle state",
                reasonCode = UnavailableReason.MALFORMED_OUTPUT,
                diagnostic = StatusDiagnostic(reasonCode = "unknown_lifecycle"),
            )
        }
    }

    private fun malformed(observedAt: Instant, code: String): SkillBillStatusOutcome =
        SkillBillStatusOutcome.Unavailable(
            observedAt = observedAt,
            summary = "Malformed Skill Bill status output",
            reasonCode = UnavailableReason.MALFORMED_OUTPUT,
            diagnostic = StatusDiagnostic(reasonCode = code),
        )

    private fun parseObject(stdout: String): JsonObject? =
        try {
            val element = JsonParser.parseString(stdout.trim())
            if (element.isJsonObject) element.asJsonObject else null
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        }

    private fun safeSummary(raw: String?, fallback: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return fallback
        // Drop absolute Unix/Windows path segments from any surfaced text.
        return AbsolutePathGuard.redact(value).take(512)
    }

    /**
     * Planning is optional context, never a reason to lose a whole status reading:
     * any missing, mistyped, or out-of-range field degrades the block to null and the
     * surrounding outcome still maps normally.
     */
    private fun JsonObject.parsePlanning(): GoalPlanningInfo? {
        val planning = getAsJsonObjectOrNull("planning") ?: return null
        val state = planning.getAsString("state")?.takeUnless { it.isBlank() } ?: return null
        val sharedPreplanPrepared = planning.getAsBoolean("shared_preplan_prepared") ?: return null
        val planned = planning.getAsStrictInt("planned_subtask_count")?.takeIf { it >= 0 } ?: return null
        val total = planning.getAsStrictInt("total_subtask_count")?.takeIf { it >= 0 } ?: return null
        return GoalPlanningInfo(
            state = state,
            sharedPreplanPrepared = sharedPreplanPrepared,
            plannedSubtaskCount = planned,
            totalSubtaskCount = total,
            currentPlanningSubtaskId = planning.getAsString("current_planning_subtask_id")
                ?.takeUnless { it.isBlank() },
            reason = planning.getAsString("reason")?.let { safeSummary(it, "") }?.takeUnless { it.isEmpty() },
        )
    }

    /**
     * Same degradation rule as [parsePlanning]: the launched model is optional context, so a
     * missing block, a non-object, or a blank/mistyped field degrades to null and the surrounding
     * outcome still maps normally.
     *
     * Over-length values degrade rather than truncate. Truncating would render a model identifier
     * that never existed — worse than showing nothing, because the operator cannot tell it was
     * clipped. The bounds mirror the schema's own `maxLength`, so the producer already rejects an
     * over-length value at its emit gate and this is the client-side floor, not the enforcement.
     *
     * A present-but-unparseable block leaves no client-side record: this mapper is deliberately
     * platform-free so it stays unit-testable, and the plugin has no logging seam that does not
     * pull in the IntelliJ platform. The producer's schema gate is where such a payload is caught.
     */
    private fun JsonObject.parseCurrentModel(): CurrentPhaseModel? {
        val currentModel = getAsJsonObjectOrNull(CURRENT_MODEL_WIRE_KEY) ?: return null
        val model = currentModel.boundedString("model", MODEL_MAX_LENGTH) ?: return null
        return CurrentPhaseModel(
            model = model,
            effort = currentModel.boundedString("effort", EFFORT_MAX_LENGTH),
            phaseId = currentModel.boundedString("phase_id", PHASE_ID_MAX_LENGTH),
        )
    }

    /**
     * Same degradation rule as [parsePlanning]: optional context, so a missing block, a
     * non-object, an unknown kind, a non-positive count, an over-length phase id, a total
     * on a kind that must not carry one, or a non-strict integer count/total degrades to null
     * and the surrounding outcome still maps.
     *
     * Count and total must be JSON number primitives with no fractional part. Gson's permissive
     * [JsonElement.asInt] is not used here: fractional numbers, numeric strings, and an explicit
     * null total reject the whole optional block rather than rendering a coerced value.
     *
     * Kind must match the controlled vocabulary exactly — surrounding whitespace is not trimmed
     * into a valid kind. Kind and count stay exactly as the producer sent them; this parser
     * never invents a total or re-labels an attempt as a semantic loop.
     */
    private fun JsonObject.parseCurrentPhaseExecution(): CurrentPhaseExecution? {
        val execution = getAsJsonObjectOrNull(CURRENT_PHASE_EXECUTION_WIRE_KEY) ?: return null
        val phaseId = execution.boundedString("phase_id", PHASE_ID_MAX_LENGTH) ?: return null
        val kind = execution.getAsStringPrimitive("kind")?.takeIf { it in CURRENT_PHASE_EXECUTION_KINDS }
            ?: return null
        val count = execution.getAsStrictInt("count")?.takeIf { it >= 1 } ?: return null
        val totalElement = execution.get("total")
        val total = when {
            totalElement == null -> null
            // Explicit null is a type error for optional total, not "absent".
            totalElement.isJsonNull -> return null
            kind != "bounded_edge" -> return null
            else -> execution.getAsStrictInt("total")?.takeIf { it >= 1 } ?: return null
        }
        return CurrentPhaseExecution(
            phaseId = phaseId,
            kind = kind,
            count = count,
            total = total,
        )
    }

    private fun JsonObject.parsePauseReason(): PauseReason? {
        val reason = getAsJsonObjectOrNull(PAUSE_REASON_WIRE_KEY) ?: return null
        val code = reason.getAsStringPrimitive("code")?.takeIf { it in PAUSE_REASON_CODES } ?: return null
        return PauseReason(
            code = code,
            label = reason.boundedString("label", PAUSE_REASON_LABEL_MAX_LENGTH),
        )
    }

    private fun JsonObject.boundedString(key: String, maxLength: Int): String? =
        getAsStringPrimitive(key)?.trim()?.takeIf { it.isNotBlank() && it.length <= maxLength }

    private fun JsonObject.getAsString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asStringOrNull()

    /** Strict: a JSON number or boolean is a type error, not text. */
    private fun JsonObject.getAsStringPrimitive(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.getAsInt(key: String): Int? =
        get(key)?.takeUnless { it.isJsonNull }?.let {
            runCatching { it.asInt }.getOrNull()
        }

    /**
     * Strict JSON integer: a number primitive with no fractional part and in [Int] range.
     * Strings, booleans, null, and fractional numbers are type errors (null), never coerced.
     */
    private fun JsonObject.getAsStrictInt(key: String): Int? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return runCatching { element.asBigDecimal.intValueExact() }.getOrNull()
    }

    /** Strict: a JSON string "true" is a type error, not a boolean. */
    private fun JsonObject.getAsBoolean(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    /** Strict and non-negative: a malformed or negative duration is dropped, never shown as work. */
    private fun JsonObject.getAsNonNegativeLong(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asLong }.getOrNull() }
            ?.takeIf { it >= 0 }

    private fun JsonObject.getAsInstant(key: String): Instant? {
        val raw = getAsString(key) ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { asString }.getOrNull()

    private fun JsonObject.parseAgentActivity(): Pair<Instant, String>? {
        val at = getAsInstant(LAST_AGENT_ACTIVITY_AT_WIRE_KEY)
        val label = getAsString(LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY)
        if (at == null && label == null) return null
        if (at == null || label == null || label !in AGENT_ACTIVITY_LABELS) {
            return null
        }
        return at to label
    }
}
