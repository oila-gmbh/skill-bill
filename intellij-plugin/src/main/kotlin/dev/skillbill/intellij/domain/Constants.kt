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

/**
 * Wire key carrying "a pause is requested but not yet consumed at a boundary".
 * Optional and goal-family-only on the contract; absence is not `false`.
 */
const val PAUSE_REQUESTED_WIRE_KEY: String = "pause_requested"

/** Wire key carrying the instant a recorded pause took effect. Optional. */
const val PAUSED_AT_WIRE_KEY: String = "paused_at"

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
const val DEFAULT_CLI_TIMEOUT_MS: Long = 10_000L

/** Bounded stdout capture for the status command. */
const val DEFAULT_STDOUT_LIMIT_BYTES: Int = 256 * 1024

/** Bounded stderr capture; never shown or persisted. */
const val DEFAULT_STDERR_LIMIT_BYTES: Int = 16 * 1024
