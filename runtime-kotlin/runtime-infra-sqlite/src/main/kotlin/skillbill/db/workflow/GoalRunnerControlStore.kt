@file:Suppress("TooManyFunctions")

package skillbill.db.workflow

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import java.sql.Connection
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.coroutines.cancellation.CancellationException

private fun goalRunnerControlSchemaError(reason: String): Nothing =
  throw InvalidWorkflowStateSchemaError("Goal runner control state: $reason")

internal class GoalRunnerControlStore(
  private val connection: Connection,
) : GoalRunnerControlRepository {
  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    selectJson(parentWorkflowId, "control_state_json")?.let(::decodeControlState) ?: GoalRunnerControlState()

  override fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState {
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, control_state_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        control_state_json = excluded.control_state_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.setString(2, JsonSupport.mapToJsonString(state.toArtifactMap()))
      statement.executeUpdate()
    }
    return state
  }

  override fun clearControlState(parentWorkflowId: String) {
    val existing = controlState(parentWorkflowId)
    // The lease and the accumulated execution total are runtime bookkeeping, not operator intent.
    // Clearing a pause must not restart the goal's execution clock at zero.
    val retained = GoalRunnerControlState(
      executionLease = existing.executionLease,
      activeDurationMs = existing.activeDurationMs,
      activeDurationAsOf = existing.activeDurationAsOf,
      currentSubtaskId = existing.currentSubtaskId,
      subtaskActiveDurationMs = existing.subtaskActiveDurationMs,
      subtaskActiveDurationAsOf = existing.subtaskActiveDurationAsOf,
    )
    if (retained != GoalRunnerControlState()) {
      persistControlState(parentWorkflowId, retained)
      return
    }
    connection.prepareStatement(
      """
      UPDATE goal_runner_controls
      SET control_state_json = NULL, updated_at = CURRENT_TIMESTAMP
      WHERE parent_workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.executeUpdate()
    }
  }

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? =
    selectJson(parentWorkflowId, "review_policy_json")?.let { decodeReviewPolicy(it) }

  override fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy {
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, review_policy_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        review_policy_json = excluded.review_policy_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.setString(2, JsonSupport.mapToJsonString(policy.toArtifactMap()))
      statement.executeUpdate()
    }
    return policy
  }

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> =
    selectJson(parentWorkflowId, "out_of_band_acceptances_json")
      ?.let(::decodeAcceptances)
      .orEmpty()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance {
    val merged = outOfBandAcceptances(parentWorkflowId) + (acceptance.subtaskId to acceptance)
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, out_of_band_acceptances_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        out_of_band_acceptances_json = excluded.out_of_band_acceptances_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.setString(
        2,
        JsonSupport.valueToJsonElement(
          merged.values.sortedBy(GoalRunnerOutOfBandAcceptance::subtaskId)
            .map(GoalRunnerOutOfBandAcceptance::toArtifactMap),
        ).toString(),
      )
      statement.executeUpdate()
    }
    return acceptance
  }

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) {
    connection.prepareStatement(
      """
      UPDATE goal_runner_controls
      SET out_of_band_acceptances_json = '[]', updated_at = CURRENT_TIMESTAMP
      WHERE parent_workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.executeUpdate()
    }
  }

  private fun selectJson(parentWorkflowId: String, column: String): String? = connection.prepareStatement(
    "SELECT $column FROM goal_runner_controls WHERE parent_workflow_id = ?",
  ).use { statement ->
    statement.setString(1, parentWorkflowId)
    statement.executeQuery().use { rows ->
      if (rows.next()) rows.getString(1)?.takeIf(String::isNotBlank) else null
    }
  }
}

private fun GoalRunnerReviewPolicy.toArtifactMap(): Map<String, Any?> = buildMap {
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

private fun GoalRunnerOutOfBandAcceptance.toArtifactMap(): Map<String, Any?> = mapOf(
  "subtask_id" to subtaskId,
  "commit_sha" to commitSha,
  "reason" to reason,
  "accepted_at" to acceptedAt,
)

private fun GoalRunnerExecutionLease.toArtifactMap(): Map<String, Any?> = mapOf(
  "generation" to generation,
  "owner_token" to ownerToken,
  "host_identity" to hostIdentity,
  "boot_identity" to bootIdentity,
  "pid" to pid,
  "process_birth_token" to processBirthToken,
  "heartbeat_at" to heartbeatAt,
  "expires_at" to expiresAt,
)

private fun GoalRunnerControlState.toArtifactMap(): Map<String, Any?> = mapOf(
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
private fun decodeControlState(raw: String): GoalRunnerControlState {
  val state = JsonSupport.parseObjectOrNull(raw)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: goalRunnerControlSchemaError("durable record must be an object.")
  val allowedKeys = setOf(
    "stop_after_subtask_id",
    "pause_requested",
    "pause_consumed",
    "paused",
    "pause_reason",
    "paused_at",
    "stop_after_consumed",
    "repository_identity",
    "execution_lease",
    "active_duration_ms",
    "active_duration_as_of",
    "current_subtask_id",
    "subtask_active_duration_ms",
    "subtask_active_duration_as_of",
  )
  state.keys.forEach { key ->
    if (key !in allowedKeys) {
      goalRunnerControlSchemaError("has unsupported field '$key'.")
    }
  }
  return GoalRunnerControlState(
    stopAfterSubtaskId = state["stop_after_subtask_id"].toPositiveIntOrNull("stop_after_subtask_id"),
    pauseRequested = state.booleanOrDefault("pause_requested", false),
    pauseConsumed = state.booleanOrDefault("pause_consumed", false),
    paused = state.booleanOrDefault("paused", false),
    pauseReason = state.nullableString("pause_reason"),
    pausedAt = state.nullableString("paused_at") ?: legacyPausedAt(state),
    stopAfterConsumed = state.booleanOrDefault("stop_after_consumed", false),
    repositoryIdentity = state.nullableString("repository_identity"),
    executionLease = state["execution_lease"]?.let(::decodeExecutionLease),
    activeDurationMs = state.nonNegativeLongOrDefault("active_duration_ms"),
    activeDurationAsOf = state.nullableString("active_duration_as_of"),
    currentSubtaskId = state["current_subtask_id"].toPositiveIntOrNull("current_subtask_id"),
    subtaskActiveDurationMs = state.nonNegativeLongOrDefault("subtask_active_duration_ms"),
    subtaskActiveDurationAsOf = state.nullableString("subtask_active_duration_as_of"),
  )
}

// A record written before the active clock existed simply has no accumulated time yet, which reads
// as "never observed executing" and starts accumulating on the next heartbeat. Numeric coercion
// matches the sibling decoders: a surprising-but-integral primitive must not fail the whole control
// row, because that would take status, pause, resume, and the lease down with it.
private fun Map<String, Any?>.nonNegativeLongOrDefault(key: String): Long = when (val value = this[key]) {
  null -> 0L
  is BigInteger -> runCatching { value.longValueExact() }.getOrNull()
  is BigDecimal -> runCatching { value.toBigIntegerExact().longValueExact() }.getOrNull()
  is Number -> value.toLong()
  else -> null
}?.takeIf { it >= 0 }
  ?: goalRunnerControlSchemaError("field '$key' must be a non-negative integer.")

/**
 * Pause timestamp for a durable record written before `paused_at` existed. Downstream consumers read
 * it as "paused, time unknown"; it is deliberately not a plausible pause time.
 */
const val LEGACY_UNKNOWN_PAUSED_AT: String = "1970-01-01T00:00:00Z"

/**
 * Backfill on the raw map, before the [GoalRunnerControlState] constructor runs, so a pre-existing
 * paused record never hard-fails the paused/pausedAt invariant. The lease heartbeat is the closest
 * durable evidence of when the runner stopped; the sentinel covers records with no lease at all.
 */
private fun legacyPausedAt(state: Map<String, Any?>): String? {
  if (!state.booleanOrDefault("paused", false)) return null
  val lease = state["execution_lease"]?.let(JsonSupport::anyToStringAnyMap)
  return lease?.nullableString("heartbeat_at") ?: LEGACY_UNKNOWN_PAUSED_AT
}

private fun decodeExecutionLease(raw: Any?): GoalRunnerExecutionLease {
  val lease = JsonSupport.anyToStringAnyMap(raw)
    ?: goalRunnerControlSchemaError("execution lease must be an object or null.")
  val allowedKeys = setOf(
    "generation",
    "owner_token",
    "host_identity",
    "boot_identity",
    "pid",
    "process_birth_token",
    "heartbeat_at",
    "expires_at",
  )
  lease.keys.forEach { key ->
    if (key !in allowedKeys) {
      goalRunnerControlSchemaError("execution lease has unsupported field '$key'.")
    }
  }
  return GoalRunnerExecutionLease(
    generation = lease["generation"].toPositiveLong("generation"),
    ownerToken = lease.requiredString("owner_token"),
    hostIdentity = lease.requiredString("host_identity"),
    bootIdentity = lease.requiredString("boot_identity"),
    pid = lease["pid"].toPositiveLong("pid"),
    processBirthToken = lease.requiredString("process_birth_token"),
    heartbeatAt = lease.requiredString("heartbeat_at"),
    expiresAt = lease.requiredString("expires_at"),
  )
}

private fun Map<String, Any?>.booleanOrDefault(key: String, default: Boolean): Boolean {
  if (!containsKey(key)) return default
  return when (val value = this[key]) {
    null -> goalRunnerControlSchemaError("field '$key' must be a boolean.")
    is Boolean -> value
    else -> goalRunnerControlSchemaError("field '$key' must be a boolean.")
  }
}

private fun Map<String, Any?>.nullableString(key: String): String? = when (val value = this[key]) {
  null -> null
  is String -> value
  else -> goalRunnerControlSchemaError("field '$key' must be a string or null.")
}

private fun Map<String, Any?>.requiredString(key: String): String = when (val value = this[key]) {
  is String -> value.takeIf(String::isNotBlank)
    ?: goalRunnerControlSchemaError("execution lease field '$key' must not be blank.")
  else -> goalRunnerControlSchemaError("execution lease field '$key' must be a nonblank string.")
}

private fun Any?.toPositiveLong(key: String): Long = when (this) {
  is Byte -> toLong()
  is Short -> toLong()
  is Int -> toLong()
  is Long -> this
  is BigInteger -> runCatching { longValueExact() }.getOrNull()
  is BigDecimal -> runCatching { toBigIntegerExact().longValueExact() }.getOrNull()
  is Number -> runCatching { BigDecimal(toString()).longValueExact() }.getOrNull()
  else -> null
}?.also {
  if (it <= 0) goalRunnerControlSchemaError("execution lease field '$key' must be positive.")
} ?: goalRunnerControlSchemaError("execution lease field '$key' must be a positive integer.")

private fun Any?.toPositiveIntOrNull(key: String): Int? = when (this) {
  null -> null
  is Byte -> toInt()
  is Short -> toInt()
  is Int -> this
  is Long -> takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
  is BigInteger -> runCatching { intValueExact() }.getOrNull()
  is BigDecimal -> runCatching { toBigIntegerExact().intValueExact() }.getOrNull()
  is Number -> runCatching { BigDecimal(toString()).intValueExact() }.getOrNull()
  else -> goalRunnerControlSchemaError("field '$key' must be a positive integer or null.")
}?.also {
  if (it <= 0) goalRunnerControlSchemaError("field '$key' must be positive.")
} ?: if (this == null) {
  null
} else {
  goalRunnerControlSchemaError("field '$key' must be a positive integer or null.")
}

private fun decodeReviewPolicy(raw: String): GoalRunnerReviewPolicy {
  val policy = JsonSupport.parseObjectOrNull(raw)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: goalRunnerControlSchemaError("review policy durable record must be an object.")
  val mode = policy["code_review_mode"] as? String
    ?: goalRunnerControlSchemaError("review policy durable record is missing code_review_mode.")
  val codeReviewMode = CodeReviewExecutionMode.fromWire(mode)
  val addOns = (policy["agent_addon_selection"] as? List<*>).orEmpty().mapIndexed { index, value ->
    val entry = JsonSupport.anyToStringAnyMap(value)
      ?: goalRunnerControlSchemaError("review policy durable add-on entry $index must be a map.")
    PersistedAgentAddonSelectionEntry(
      slug = entry["slug"] as? String
        ?: goalRunnerControlSchemaError("review policy durable add-on entry $index is missing slug."),
      sourceIdentity = entry["source_identity"] as? String
        ?: goalRunnerControlSchemaError("review policy durable add-on entry $index is missing source_identity."),
      contentSha256 = entry["content_sha256"] as? String
        ?: goalRunnerControlSchemaError("review policy durable add-on entry $index is missing content_sha256."),
    )
  }
  return GoalRunnerReviewPolicy(codeReviewMode, AgentAddonSelection(addOns))
}

private fun decodeAcceptances(raw: String): Map<Int, GoalRunnerOutOfBandAcceptance> {
  val entries = try {
    JsonSupport.json.parseToJsonElement(raw)
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    goalRunnerControlSchemaError("acceptance durable record is not valid JSON.")
  }
  val values = (JsonSupport.jsonElementToValue(entries) as? List<*>)
    ?: goalRunnerControlSchemaError("acceptance durable record must be a list.")
  return values.associate { value ->
    val entry = JsonSupport.anyToStringAnyMap(value)
      ?: goalRunnerControlSchemaError("acceptance durable record entries must be maps.")
    val acceptance = GoalRunnerOutOfBandAcceptance(
      subtaskId = (entry["subtask_id"] as? Number)?.toInt()
        ?: goalRunnerControlSchemaError("acceptance durable record entry is missing subtask_id."),
      commitSha = entry["commit_sha"] as? String
        ?: goalRunnerControlSchemaError("acceptance durable record entry is missing commit_sha."),
      reason = entry["reason"] as? String
        ?: goalRunnerControlSchemaError("acceptance durable record entry is missing reason."),
      acceptedAt = entry["accepted_at"] as? String
        ?: goalRunnerControlSchemaError("acceptance durable record entry is missing accepted_at."),
    )
    acceptance.subtaskId to acceptance
  }
}
