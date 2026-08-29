package skillbill.goalrunner.model

import skillbill.workflow.decomposition.model.DecompositionManifest

data class GoalRunnerExecutionLease(
  val generation: Long,
  val ownerToken: String,
  val hostIdentity: String,
  val bootIdentity: String,
  val pid: Long,
  val processBirthToken: String,
  val heartbeatAt: String,
  val expiresAt: String,
) {
  init {
    require(generation > 0) { "execution lease generation must be positive." }
    require(ownerToken.isNotBlank()) { "execution lease ownerToken must not be blank." }
    require(hostIdentity.isNotBlank()) { "execution lease hostIdentity must not be blank." }
    require(bootIdentity.isNotBlank()) { "execution lease bootIdentity must not be blank." }
    require(pid > 0) { "execution lease pid must be positive." }
    require(processBirthToken.isNotBlank()) { "execution lease processBirthToken must not be blank." }
    require(heartbeatAt.isNotBlank()) { "execution lease heartbeatAt must not be blank." }
    require(expiresAt.isNotBlank()) { "execution lease expiresAt must not be blank." }
  }
}

/** An operator asked for a pause and the runner honoured it at its next boundary. */
const val GOAL_PAUSE_REASON_OPERATOR_REQUEST: String = "operator_request"

/** The runner reached the operator's `stop_after` subtask target. */
const val GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK: String = "stop_after_subtask"

/** An operator ran `goal stop`: intent written durably before the runner process was terminated. */
const val GOAL_PAUSE_REASON_OPERATOR_STOP: String = "operator_stop"

/** The runner process was terminated from outside; its shutdown hook recorded the interruption. */
const val GOAL_PAUSE_REASON_RUNNER_INTERRUPTED: String = "runner_interrupted"

/**
 * Longest heartbeat gap that still counts as execution. A live runner heartbeats every
 * `GoalRunnerExecutionCoordinator.HEARTBEAT_SECONDS`; a larger gap means the runner was gone and the
 * interval was downtime, so it must not be folded into the active total.
 */
const val GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS: Long = 20_000

/** Durable parent-owned pause, stop-after, and execution state, separate from the checked-in manifest. */
data class GoalRunnerControlState(
  val stopAfterSubtaskId: Int? = null,
  val pauseRequested: Boolean = false,
  val pauseConsumed: Boolean = false,
  val paused: Boolean = false,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val stopAfterConsumed: Boolean = false,
  val repositoryIdentity: String? = null,
  val executionLease: GoalRunnerExecutionLease? = null,
  // Time this goal spent actually executing, which is not the wall clock since it was opened: a goal
  // sits blocked, paused, or simply unattended between runs, and those gaps are not work.
  val activeDurationMs: Long = 0,
  val activeDurationAsOf: String? = null,
  val currentSubtaskId: Int? = null,
  val subtaskActiveDurationMs: Long = 0,
  val subtaskActiveDurationAsOf: String? = null,
) {
  init {
    stopAfterSubtaskId?.let { require(it > 0) { "stopAfterSubtaskId must be positive when provided." } }
    require(activeDurationMs >= 0) { "activeDurationMs must not be negative." }
    activeDurationAsOf?.let { require(it.isNotBlank()) { "activeDurationAsOf must not be blank when provided." } }
    currentSubtaskId?.let { require(it > 0) { "currentSubtaskId must be positive when provided." } }
    require(subtaskActiveDurationMs >= 0) { "subtaskActiveDurationMs must not be negative." }
    subtaskActiveDurationAsOf?.let {
      require(it.isNotBlank()) { "subtaskActiveDurationAsOf must not be blank when provided." }
    }
    require(!pauseConsumed || pauseRequested) {
      "pauseConsumed cannot be true when pauseRequested is false."
    }
    require(!stopAfterConsumed || stopAfterSubtaskId != null) {
      "stopAfterConsumed requires stopAfterSubtaskId."
    }
    pauseReason?.let { require(it.isNotBlank()) { "pauseReason must not be blank when provided." } }
    require(!paused || pauseReason != null) { "paused control state requires pauseReason." }
    pausedAt?.let { require(it.isNotBlank()) { "pausedAt must not be blank when provided." } }
    require(!paused || pausedAt != null) { "paused control state requires pausedAt." }
    repositoryIdentity?.let {
      require(it.isNotBlank()) { "repositoryIdentity must not be blank when provided." }
    }
  }

  fun reconciledForCurrentSubtask(manifestSubtaskId: Int): GoalRunnerControlState {
    if (manifestSubtaskId <= 0) return this
    if (currentSubtaskId == manifestSubtaskId) return this
    return copy(
      currentSubtaskId = manifestSubtaskId,
      subtaskActiveDurationMs = 0,
      subtaskActiveDurationAsOf = null,
    )
  }

  fun requiresPauseBoundary(manifest: DecompositionManifest): Boolean = pauseRequested || paused || (
    stopAfterSubtaskId != null &&
      !stopAfterConsumed &&
      manifest.subtasks.any { it.id == stopAfterSubtaskId && it.status == "complete" }
    )
}
