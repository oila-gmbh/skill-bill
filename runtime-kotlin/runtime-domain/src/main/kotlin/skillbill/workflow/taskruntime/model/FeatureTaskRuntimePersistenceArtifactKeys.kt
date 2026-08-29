package skillbill.workflow.taskruntime.model

const val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY: String = "feature_task_runtime_phase_records"
const val FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY: String = "feature_task_runtime_phase_ledger"
const val FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY: String = "goal_planning_import"
const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY: String = "operator_block_retry"
const val FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY: String = "feature_task_runtime_review_generation"
const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH: Int = 1000

/**
 * Durable evidence that a resume adopted a launcher-supplied goal-continuation field because the
 * durable row predated that field's contract. Separate from the goal-continuation artifact map so
 * [goalContinuationKeys] / rejectUnknownGoalContinuationKeys stay untouched.
 */
const val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY: String =
  "goal_continuation_field_adoption"
const val FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT: Int = 200

/**
 * Durable audit-gap progress artifact: the criterion refs and repository fingerprint of the most
 * recent completed gaps audit. The no-progress check reads its OLD value as the previous round and
 * overwrites it with the settling audit's refs+fingerprint, so a fresh process between audits
 * compares consecutive rounds instead of starting from an empty previous set.
 */
const val FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY: String =
  "feature_task_runtime_audit_gap_progress"

const val AUDIT_GAP_PAUSE_KIND_NO_PROGRESS: String = "no_progress"
const val AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD: String = "warn_threshold"
const val AUDIT_GAP_PAUSE_DECISION_RETRY_FIX: String = "retry_fix"
const val AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK: String = "abandon_subtask"

/**
 * Durable audit-gap pause artifact: the pause the runtime minted at an audit->implement seam so the
 * tree stops being re-entered. The pause and its operator decision are the authority across a crash.
 */
const val FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY: String =
  "feature_task_runtime_audit_gap_pause"

/**
 * Durable append-only history of implementation ATTEMPTS, structurally separate from
 * [FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY]. The two keys must never merge: the phase-records
 * store is put()-replaced per phase id and therefore holds only the LATEST implement output, which
 * cannot carry the prior receipt a semantic continuation segment needs. This store is appended to and
 * bounded, so retry and crash resume reconstruct the same continuation projection from it.
 *
 * An absent key decodes to zero prior attempts, so a workflow created before this contract needs no
 * DDL migration.
 */
const val FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY: String =
  "feature_task_runtime_implementation_attempts"

/** Mirrors the schema's `attempts.maxItems`; a schema-valid store can therefore never overflow it. */
const val FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_LIMIT: Int = 64

const val FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY: String =
  "feature_task_runtime_finding_verification_checkpoint"

const val FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY: String =
  "feature_task_runtime_finding_verification_boundary_selection"

const val FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY: String =
  "feature_task_runtime_finding_verification_dispositions"

/**
 * Durable run-scoped resolved feature branch. The runtime resolves a non-default feature branch
 * before any file-mutating phase runs and persists it here exactly once, so resume re-attaches to
 * the same branch and never creates a duplicate or divergent one.
 */
const val FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY: String = "feature_task_runtime_resolved_branch"
const val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY: String = "goal_continuation"
const val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY: String = "goal_continuation_outcome"

/**
 * Delivered-projection tier of the durable store, structurally separate from
 * [FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY]. The two keys must never merge: the phase-records
 * store holds complete validated phase output as PRIVATE EVIDENCE, while this store holds the exact
 * envelope a consumer phase was delivered. Merging them would let a round trip hand a consumer the
 * private artifact in place of its projection, which is the whole failure this split prevents.
 */
const val FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY: String =
  "feature_task_runtime_delivered_projections"

/** Terminal status persisted on a phase record that the runtime blocked on. */
const val FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED: String = "blocked"

/** Unstarted work: what a reopened phase record carries until the phase runs again. */
const val FEATURE_TASK_RUNTIME_PHASE_STATUS_PENDING: String = "pending"

/** Non-terminal and resumable: the subtask waits on a bounded operator decision, it is not blocked. */
const val FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED: String = "paused"

/** Terminal success. A completed phase's launch context is history, never current state. */
const val FEATURE_TASK_RUNTIME_PHASE_STATUS_COMPLETED: String = "completed"
