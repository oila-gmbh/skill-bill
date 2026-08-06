package dev.skillbill.intellij.domain

/**
 * Pinned wire contract version for `skill-bill work status --format json`.
 * Must match `IDE_STATUS_CONTRACT_VERSION` / ide-status-schema.yaml const.
 */
const val IDE_STATUS_CONTRACT_VERSION: String = "0.1"

/** Default poll interval — seconds-scale, never sub-second. */
const val DEFAULT_REFRESH_INTERVAL_SECONDS: Long = 15L

/** Process timeout for a single status poll. */
const val DEFAULT_CLI_TIMEOUT_MS: Long = 10_000L

/** Bounded stdout capture for the status command. */
const val DEFAULT_STDOUT_LIMIT_BYTES: Int = 256 * 1024

/** Bounded stderr capture; never shown or persisted. */
const val DEFAULT_STDERR_LIMIT_BYTES: Int = 16 * 1024
