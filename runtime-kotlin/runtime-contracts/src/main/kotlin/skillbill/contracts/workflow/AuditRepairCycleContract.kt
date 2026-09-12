package skillbill.contracts.workflow

const val FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION: String = "0.2"

object AuditRepairCycleKeys {
  const val CONTRACT_VERSION: String = "contract_version"
  const val WORKFLOW_ID: String = "workflow_id"
  const val AUDIT_ATTEMPT: String = "audit_attempt"
  const val EXECUTION_ID: String = "execution_id"
  const val SESSION_ID: String = "session_id"
  const val CYCLE_ID: String = "cycle_id"
  const val OWNER_TOKEN: String = "owner_token"
  const val FENCING_GENERATION: String = "fencing_generation"
  const val CRITERION_REFS: String = "criterion_refs"
  const val LEGACY_EVIDENCE: String = "legacy_evidence"
  const val REVISIONS: String = "revisions"
  const val REVISION: String = "revision"
  const val REQUEST_ID: String = "request_id"
  const val EXPECTED_REVISION: String = "expected_revision"
  const val STAGE: String = "stage"
  const val RECORDED_AT: String = "recorded_at"
  const val ASSESSMENT: String = "assessment"
  const val CHECKPOINT: String = "checkpoint"
  const val CHECKPOINT_ID: String = "checkpoint_id"
  const val CHECKPOINT_PATHS: String = "checkpoint_paths"
  const val REPOSITORY_FINGERPRINT: String = "repository_fingerprint"
  const val CRITERIA: String = "criteria"
  const val CRITERION_REF: String = "criterion_ref"
  const val SATISFIED: String = "satisfied"
  const val EVIDENCE: String = "evidence"
  const val REPAIR_ID: String = "repair_id"
  const val REPAIR_GUIDANCE: String = "repair_guidance"
  const val PENDING_VALIDATION: String = "pending_validation"
  const val VALUE: String = "value"
  const val CHANGED_PATHS: String = "changed_paths"
  const val CHECKPOINT_INTENT: String = "checkpoint_intent"
  const val REPAIR_OUTCOMES: String = "repair_outcomes"
  const val REASON: String = "reason"
}

object AuditRepairCycleStatusKeys {
  const val FIRST_PASS_CONVERGENCE: String = "first_pass_convergence"
  const val AUDIT_GAP_ITERATION_COUNT: String = "audit_gap_iteration_count"
  const val STAGE: String = "stage"
  const val UNRESOLVED_CRITERION_REFS: String = "unresolved_criterion_refs"
  const val REPAIR_ROUND_COUNT: String = "repair_round_count"
  const val LAST_CHECKPOINT_ID: String = "last_checkpoint_id"
  const val EXECUTION_ID: String = "execution_id"
  const val SESSION_ID: String = "session_id"
  const val OPERATOR_REASON: String = "operator_reason"
}

object AuditRepairEventKeys {
  const val EVENT_NAME: String = "skillbill_audit_repair_transition"
  const val PREVIOUS_STAGE: String = "previous_stage"
  const val RECOVERY: String = "recovery"
  const val UNRESOLVED_COUNT: String = "unresolved_count"
}
