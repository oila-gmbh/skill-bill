package skillbill.contracts.workflow

/**
 * SKILL-48 Subtask 2a: `WorkflowContracts` is intentionally pure-Kotlin
 * and free of networknt / Jackson dependencies. Schema validation runs
 * at every CALLER seam that produces or replays the snapshot envelope:
 *
 *  - `WorkflowEngine.openRecord` / `updateRecord` validate before
 *    returning a fresh snapshot to the application layer.
 *  - `WorkflowEngine.fullPayload` / `summaryPayload` validate the
 *    snapshot envelope before deriving the public payload.
 *
 * Scope of validation: ONLY the snapshot envelope (the schema-shaped
 * map produced by `WorkflowEngine.validatedSnapshotMap`) is schema-
 * validated. The `resumePayload` / `continuePayload` derivative shapes
 * extend beyond that envelope with non-snapshot fields (`resume_mode`,
 * `resume_step_id`, `can_resume`, `continue_status`, `continuation_brief`,
 * `step_artifacts`, the implement-only extra fields, etc.) and are
 * intentionally NOT schema-validated. If pinning those derivative
 * shapes ever becomes necessary, define separate continue/resume
 * envelope schemas under `orchestration/contracts/` — do not retrofit
 * the snapshot schema with derivative fields.
 *
 * Keeping the validator out of this module preserves the contract
 * layer as a thin, dependency-free type surface.
 */
object WorkflowContracts {
  fun fullWorkflowPayload(fields: Map<String, Any?>): Map<String, Any?> = orderedPayload(
    fields,
    listOf(
      "workflow_id",
      "session_id",
      "workflow_name",
      "contract_version",
      "workflow_status",
      "current_step_id",
      "steps",
      "artifacts",
      "started_at",
      "updated_at",
      "finished_at",
    ),
  )

  fun summaryWorkflowPayload(fields: Map<String, Any?>): Map<String, Any?> = orderedPayload(
    fields,
    listOf(
      "workflow_id",
      "session_id",
      "workflow_name",
      "contract_version",
      "workflow_status",
      "current_step_id",
      "started_at",
      "updated_at",
      "finished_at",
    ),
  )

  fun resumePayload(basePayload: Map<String, Any?>, resumeFields: Map<String, Any?>): Map<String, Any?> =
    LinkedHashMap(basePayload).apply {
      put("resume_mode", resumeFields["resume_mode"])
      put("resume_step_id", resumeFields["resume_step_id"])
      put("last_completed_step_id", resumeFields["last_completed_step_id"])
      put("available_artifacts", resumeFields["available_artifacts"])
      put("required_artifacts", resumeFields["required_artifacts"])
      put("missing_artifacts", resumeFields["missing_artifacts"])
      put("can_resume", resumeFields["can_resume"])
      put("next_action", resumeFields["next_action"])
    }

  fun continuePayload(resumePayload: Map<String, Any?>, continueFields: Map<String, Any?>): Map<String, Any?> =
    LinkedHashMap(resumePayload).apply {
      put("skill_name", continueFields["skill_name"])
      put("continuation_mode", "resume_existing_workflow")
      put("workflow_status_before_continue", continueFields["workflow_status_before_continue"])
      put("continue_status", continueFields["continue_status"])
      put("continue_step_id", continueFields["continue_step_id"])
      put("continue_step_label", continueFields["continue_step_label"])
      put("continue_step_directive", continueFields["continue_step_directive"])
      put("reference_sections", continueFields["reference_sections"])
      put("step_artifact_keys", continueFields["step_artifact_keys"])
      put("step_artifacts", continueFields["step_artifacts"])
      (continueFields["extra_fields"] as? Map<*, *>)?.forEach { (key, value) ->
        if (key is String) {
          put(key, value)
        }
      }
      put("session_summary", continueFields["session_summary"])
      put("continuation_brief", continueFields["continuation_brief"])
      put("continuation_entry_prompt", continueFields["continuation_entry_prompt"])
    }

  private fun orderedPayload(fields: Map<String, Any?>, keys: List<String>): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply {
      keys.forEach { key -> put(key, fields[key]) }
    }
}
