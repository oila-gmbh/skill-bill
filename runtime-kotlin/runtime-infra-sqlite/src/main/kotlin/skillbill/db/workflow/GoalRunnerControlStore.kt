package skillbill.db.workflow

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import java.sql.Connection

internal fun goalRunnerControlSchemaError(reason: String): Nothing =
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
      statement.setString(2, JsonCodec.mapToJsonString(state.toArtifactMap()))
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
      statement.setString(2, JsonCodec.mapToJsonString(policy.toArtifactMap()))
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
        JsonCodec.valueToJsonElement(
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
