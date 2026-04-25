package skillbill.db

import skillbill.contracts.JsonSupport
import skillbill.telemetry.model.PrDescriptionGeneratedRecord

fun featureImplementStartedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    "session_id" to row.stringOrEmpty("session_id"),
    "issue_key_provided" to row.booleanFromInt("issue_key_provided"),
    "issue_key_type" to row.stringOrEmpty("issue_key_type"),
    "spec_input_types" to JsonSupport.parseArrayOrEmpty(row.stringOrEmpty("spec_input_types")),
    "spec_word_count" to row.intOrZero("spec_word_count"),
    "feature_size" to row.stringOrEmpty("feature_size"),
    "rollout_needed" to row.booleanFromInt("rollout_needed"),
    "acceptance_criteria_count" to row.intOrZero("acceptance_criteria_count"),
    "open_questions_count" to row.intOrZero("open_questions_count"),
  ).apply {
    if (level == "full") {
      put("feature_name", row.stringOrEmpty("feature_name"))
      put("spec_summary", row.stringOrEmpty("spec_summary"))
    }
  }

fun featureImplementFinishedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> =
  featureImplementStartedPayload(row, level).toMutableMap().apply {
    put("completion_status", row.stringOrEmpty("completion_status"))
    put("plan_correction_count", row.intOrZero("plan_correction_count"))
    put("plan_task_count", row.intOrZero("plan_task_count"))
    put("plan_phase_count", row.intOrZero("plan_phase_count"))
    put("feature_flag_used", row.booleanFromInt("feature_flag_used"))
    put("feature_flag_pattern", row.stringOrEmpty("feature_flag_pattern").ifBlank { "none" })
    put("files_created", row.intOrZero("files_created"))
    put("files_modified", row.intOrZero("files_modified"))
    put("tasks_completed", row.intOrZero("tasks_completed"))
    put("review_iterations", row.intOrZero("review_iterations"))
    put("audit_result", row.stringOrEmpty("audit_result").ifBlank { "skipped" })
    put("audit_iterations", row.intOrZero("audit_iterations"))
    put("validation_result", row.stringOrEmpty("validation_result").ifBlank { "skipped" })
    put("boundary_history_written", row.booleanFromInt("boundary_history_written"))
    put("boundary_history_value", row.stringOrEmpty("boundary_history_value").ifBlank { "none" })
    put("pr_created", row.booleanFromInt("pr_created"))
    put("duration_seconds", durationSeconds(row))
    put("child_steps", JsonSupport.parseArrayOrEmpty(row.stringOrEmpty("child_steps_json")))
    if (level == "full") {
      put("plan_deviation_notes", row.stringOrEmpty("plan_deviation_notes"))
    }
  }

fun qualityCheckStartedPayload(row: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
  "session_id" to row.stringOrEmpty("session_id"),
  "routed_skill" to row.stringOrEmpty("routed_skill"),
  "detected_stack" to row.stringOrEmpty("detected_stack"),
  "scope_type" to row.stringOrEmpty("scope_type"),
  "initial_failure_count" to row.intOrZero("initial_failure_count"),
)

fun qualityCheckFinishedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> =
  qualityCheckStartedPayload(row).toMutableMap().apply {
    put("final_failure_count", row.intOrZero("final_failure_count"))
    put("iterations", row.intOrZero("iterations"))
    put("result", row.stringOrEmpty("result").ifBlank { "skipped" })
    put("duration_seconds", durationSeconds(row))
    if (level == "full") {
      put("failing_check_names", JsonSupport.parseArrayOrEmpty(row.stringOrEmpty("failing_check_names")))
      put("unsupported_reason", row.stringOrEmpty("unsupported_reason"))
    }
  }

fun featureVerifyStartedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> = linkedMapOf<String, Any?>(
  "session_id" to row.stringOrEmpty("session_id"),
  "acceptance_criteria_count" to row.intOrZero("acceptance_criteria_count"),
  "rollout_relevant" to row.booleanFromInt("rollout_relevant"),
).apply {
  if (level == "full") {
    put("spec_summary", row.stringOrEmpty("spec_summary"))
  }
}

fun featureVerifyFinishedPayload(row: Map<String, Any?>, level: String): Map<String, Any?> =
  featureVerifyStartedPayload(row, level).toMutableMap().apply {
    put("feature_flag_audit_performed", row.booleanFromInt("feature_flag_audit_performed"))
    put("review_iterations", row.intOrZero("review_iterations"))
    put("audit_result", row.stringOrEmpty("audit_result").ifBlank { "skipped" })
    put("completion_status", row.stringOrEmpty("completion_status"))
    put("history_relevance", row.stringOrEmpty("history_relevance").ifBlank { "none" })
    put("history_helpfulness", row.stringOrEmpty("history_helpfulness").ifBlank { "none" })
    put("duration_seconds", durationSeconds(row))
    if (level == "full") {
      put("gaps_found", JsonSupport.parseArrayOrEmpty(row.stringOrEmpty("gaps_found")))
    }
  }

fun prDescriptionPayload(record: PrDescriptionGeneratedRecord, level: String): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    "session_id" to record.sessionId,
    "commit_count" to record.commitCount,
    "files_changed_count" to record.filesChangedCount,
    "was_edited_by_user" to record.wasEditedByUser,
    "pr_created" to record.prCreated,
  ).apply {
    if (level == "full") {
      put("pr_title", record.prTitle)
    }
  }
