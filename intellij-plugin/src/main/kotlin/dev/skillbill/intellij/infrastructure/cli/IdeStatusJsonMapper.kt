package dev.skillbill.intellij.infrastructure.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dev.skillbill.intellij.domain.IDE_STATUS_CONTRACT_VERSION
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
        val updatedAt = root.getAsInstant("updated_at")

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

        if (freshness == "stale") {
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
            )
        }

        return when (lifecycle) {
            "active", "paused" -> {
                if (repositoryIdentity.isNullOrBlank() || stepId.isNullOrBlank() || stepLabel.isNullOrBlank() || updatedAt == null) {
                    malformed(observedAt, "incomplete_active_payload")
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
            )

            "idle", "terminal" -> SkillBillStatusOutcome.Idle(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
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

    private fun JsonObject.getAsString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asStringOrNull()

    private fun JsonObject.getAsInt(key: String): Int? =
        get(key)?.takeUnless { it.isJsonNull }?.let {
            runCatching { it.asInt }.getOrNull()
        }

    private fun JsonObject.getAsInstant(key: String): Instant? {
        val raw = getAsString(key) ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { asString }.getOrNull()
}
