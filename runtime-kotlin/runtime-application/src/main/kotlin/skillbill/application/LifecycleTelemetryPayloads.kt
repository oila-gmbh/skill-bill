package skillbill.application

import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest

private const val STATUS_OK = "ok"
private const val STATUS_SKIPPED = "skipped"

fun lifecycleOkPayload(sessionId: String): Map<String, Any?> = mapOf("status" to STATUS_OK, "session_id" to sessionId)

fun lifecycleSkippedPayload(sessionId: String): Map<String, Any?> =
  mapOf("status" to STATUS_SKIPPED, "session_id" to sessionId)

fun lifecycleErrorPayload(sessionId: String, error: String): Map<String, Any?> =
  mapOf("status" to "error", "session_id" to sessionId, "error" to error)

fun orchestratedStartedSkippedPayload(): Map<String, Any?> =
  mapOf("mode" to "orchestrated", "status" to "skipped_in_orchestrated_mode")

fun QualityCheckFinishedRequest.orchestratedPayload(level: String): Map<String, Any?> =
  mapOf("mode" to "orchestrated", "telemetry_payload" to qualityCheckPayload(level))

fun FeatureVerifyFinishedRequest.orchestratedPayload(level: String): Map<String, Any?> =
  mapOf("mode" to "orchestrated", "telemetry_payload" to featureVerifyPayload(level))

fun PrDescriptionGeneratedRequest.orchestratedPayload(level: String): Map<String, Any?> =
  mapOf("mode" to "orchestrated", "telemetry_payload" to prDescriptionPayload(level))

private fun QualityCheckFinishedRequest.qualityCheckPayload(level: String): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    "routed_skill" to routedSkill,
    "detected_stack" to detectedStack,
    "scope_type" to scopeType,
    "initial_failure_count" to initialFailureCount,
    "final_failure_count" to finalFailureCount,
    "iterations" to iterations,
    "result" to result,
    "duration_seconds" to durationSeconds,
    "skill" to "bill-quality-check",
  ).apply {
    if (level == "full") {
      put("failing_check_names", failingCheckNames)
      put("unsupported_reason", unsupportedReason)
    }
  }

private fun FeatureVerifyFinishedRequest.featureVerifyPayload(level: String): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    "acceptance_criteria_count" to acceptanceCriteriaCount,
    "rollout_relevant" to rolloutRelevant,
    "feature_flag_audit_performed" to featureFlagAuditPerformed,
    "review_iterations" to reviewIterations,
    "audit_result" to auditResult,
    "completion_status" to completionStatus,
    "history_relevance" to historyRelevance,
    "history_helpfulness" to historyHelpfulness,
    "duration_seconds" to durationSeconds,
    "skill" to "bill-feature-verify",
  ).apply {
    if (level == "full") {
      put("spec_summary", specSummary)
      put("gaps_found", gapsFound)
    }
  }

private fun PrDescriptionGeneratedRequest.prDescriptionPayload(level: String): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    "commit_count" to commitCount,
    "files_changed_count" to filesChangedCount,
    "was_edited_by_user" to wasEditedByUser,
    "pr_created" to prCreated,
    "skill" to "bill-pr-description",
  ).apply {
    if (level == "full") {
      put("pr_title", prTitle)
    }
  }
