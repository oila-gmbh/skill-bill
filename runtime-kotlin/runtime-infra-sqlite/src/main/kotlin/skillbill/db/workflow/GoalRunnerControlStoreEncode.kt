package skillbill.db.workflow

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy

internal fun GoalRunnerReviewPolicy.toArtifactMap(): Map<String, Any?> = buildMap {
  put("code_review_mode", codeReviewMode.wireValue)
  if (agentAddonSelection.entries.isNotEmpty()) {
    put(
      "agent_addon_selection",
      agentAddonSelection.entries.map { entry ->
        mapOf(
          "slug" to entry.slug,
          "source_identity" to entry.sourceIdentity,
          "content_sha256" to entry.contentSha256,
        )
      },
    )
  }
}

internal fun GoalRunnerOutOfBandAcceptance.toArtifactMap(): Map<String, Any?> = mapOf(
  "subtask_id" to subtaskId,
  "commit_sha" to commitSha,
  "reason" to reason,
  "accepted_at" to acceptedAt,
)

internal fun GoalRunnerExecutionLease.toArtifactMap(): Map<String, Any?> = mapOf(
  "generation" to generation,
  "owner_token" to ownerToken,
  "host_identity" to hostIdentity,
  "boot_identity" to bootIdentity,
  "pid" to pid,
  "process_birth_token" to processBirthToken,
  "heartbeat_at" to heartbeatAt,
  "expires_at" to expiresAt,
)

internal fun GoalRunnerControlState.toArtifactMap(): Map<String, Any?> = mapOf(
  "stop_after_subtask_id" to stopAfterSubtaskId,
  "pause_requested" to pauseRequested,
  "pause_consumed" to pauseConsumed,
  "paused" to paused,
  "pause_reason" to pauseReason,
  "paused_at" to pausedAt,
  "stop_after_consumed" to stopAfterConsumed,
  "repository_identity" to repositoryIdentity,
  "execution_lease" to executionLease?.toArtifactMap(),
  "active_duration_ms" to activeDurationMs,
  "active_duration_as_of" to activeDurationAsOf,
  "current_subtask_id" to currentSubtaskId,
  "subtask_active_duration_ms" to subtaskActiveDurationMs,
  "subtask_active_duration_as_of" to subtaskActiveDurationAsOf,
)

/**
 * The allowed-key whitelist is strict in both directions: an older binary reading a record that
 * carries `paused_at` fails here. Accepted — goal runner durable state is same-binary-version.
 */
