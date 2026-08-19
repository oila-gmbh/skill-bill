package dev.skillbill.intellij.domain

/**
 * Pinned wire contract version for `skill-bill work status --format json`.
 * Must match `IDE_STATUS_CONTRACT_VERSION` / ide-status-schema.yaml const.
 */
const val IDE_STATUS_CONTRACT_VERSION: String = "0.1"

/**
 * Wire problem code for "no work matches this repository". Marked on the derived
 * [SkillBillStatusOutcome.Idle] so the coordinator can tell it apart from a
 * lifecycle-derived idle without string-matching a summary.
 */
const val NO_MATCHING_WORK_REASON_CODE: String = "no_matching_work"

/**
 * Consecutive unconfirmed `no_matching_work` samples tolerated before a live display
 * settles to idle. One held poll absorbs a single-poll gap between runtime records.
 */
const val UNCORROBORATED_IDLE_TOLERANCE: Int = 1

const val POLL_FAILED_REASON_CODE: String = "poll_failed"

/**
 * Wire key carrying "a pause is requested but not yet consumed at a boundary".
 * Optional and goal-family-only on the contract; absence is not `false`.
 */
const val PAUSE_REQUESTED_WIRE_KEY: String = "pause_requested"

/**
 * Wire key carrying the model (and optional effort and phase id) the current phase launched with.
 * Optional context: absence degrades to no model, never to a lost status reading.
 */
const val CURRENT_MODEL_WIRE_KEY: String = "current_model"

/**
 * Wire key carrying the authoritative current-phase execution measure (phase, kind, count,
 * optional bounded total). Optional context: absence or a malformed block degrades to no
 * execution value, never to a lost status reading.
 */
const val CURRENT_PHASE_EXECUTION_WIRE_KEY: String = "current_phase_execution"

/**
 * Controlled vocabulary for [CurrentPhaseExecution.kind], matching the IDE status schema enum.
 * Distinguishes semantic loops/passes from gate runs, capped backward edges, and generic attempts.
 */
val CURRENT_PHASE_EXECUTION_KINDS: Set<String> = setOf(
    "pass",
    "semantic_loop",
    "gate_run",
    "bounded_edge",
    "attempt",
)

/**
 * Mirrors the `current_model` length bounds in `orchestration/contracts/ide-status-schema.yaml`.
 * A value past these is rejected rather than clipped, so the popup never shows a model identifier
 * that never existed.
 */
const val MODEL_MAX_LENGTH: Int = 120

const val EFFORT_MAX_LENGTH: Int = 40

const val PHASE_ID_MAX_LENGTH: Int = 64

/** Display bound for the composed model row; the popup has no width cap of its own. */
const val MODEL_TEXT_MAX_LENGTH: Int = 60

/** Wire key carrying the instant a recorded pause took effect. Optional. */
const val PAUSED_AT_WIRE_KEY: String = "paused_at"

const val PAUSE_REASON_WIRE_KEY: String = "pause_reason"

val PAUSE_REASON_CODES: Set<String> = setOf(
    "awaiting_operator_decision",
    "operator_request",
    "stop_after_subtask",
    "operator_stop",
    "runner_interrupted",
)

const val PAUSE_REASON_AWAITING_OPERATOR_DECISION: String = "awaiting_operator_decision"

const val PAUSE_REASON_LABEL_MAX_LENGTH: Int = 512

const val GOAL_FINDINGS_DISPLAY_COMMAND: String = "skill-bill goal findings --issue-key"

const val ACTIVE_DURATION_MS_WIRE_KEY: String = "active_duration_ms"

const val ACTIVE_DURATION_AS_OF_WIRE_KEY: String = "active_duration_as_of"

/** `workflow_family` value identifying a decomposed feature goal. */
const val FEATURE_GOAL_WORKFLOW_FAMILY: String = "feature-goal"

/** Runtime CLI verb for a graceful pause at the next subtask boundary. */
val GOAL_PAUSE_VERB: List<String> = listOf("goal", "pause")

/** Runtime CLI verb for an immediate operator stop. Termination is the runtime's. */
val GOAL_STOP_VERB: List<String> = listOf("goal", "stop")

/** Option naming the repository that owns the goal, for both mutating verbs. */
const val REPO_ROOT_OPTION: String = "--repo-root"

/** Default poll interval — seconds-scale, never sub-second. */
const val DEFAULT_REFRESH_INTERVAL_SECONDS: Long = 15L

/** Process timeout for a single status poll. */
const val DEFAULT_CLI_TIMEOUT_MS: Long = 30_000L

/** Bounded stdout capture for the status command. */
const val DEFAULT_STDOUT_LIMIT_BYTES: Int = 256 * 1024

/** Bounded stderr capture; never shown or persisted. */
const val DEFAULT_STDERR_LIMIT_BYTES: Int = 16 * 1024
