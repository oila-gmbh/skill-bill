package skillbill.db.workflow

import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.ports.idestatus.AgentActivityStampRepository
import java.sql.Connection
import java.time.Instant
import java.time.format.DateTimeParseException

internal class AgentActivityStampStore(
  private val connection: Connection,
) : AgentActivityStampRepository {
  override fun record(workflowId: String, stamp: AgentActivityStamp) {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    val existing = read(workflowId)
    if (existing != null && !stamp.recordedAt.isAfter(existing.recordedAt)) return
    connection.prepareStatement(
      """
      INSERT INTO agent_activity_stamps (workflow_id, recorded_at, label)
      VALUES (?, ?, ?)
      ON CONFLICT(workflow_id) DO UPDATE SET
        recorded_at = excluded.recorded_at,
        label = excluded.label
      WHERE excluded.recorded_at > agent_activity_stamps.recorded_at
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, stamp.recordedAt.toString())
      statement.setString(3, stamp.label.wireValue)
      statement.executeUpdate()
    }
  }

  override fun read(workflowId: String): AgentActivityStamp? {
    if (workflowId.isBlank()) return null
    return connection.prepareStatement(
      """
      SELECT recorded_at, label
      FROM agent_activity_stamps
      WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) return null
        val recordedAt = parseInstant(resultSet.getString("recorded_at")) ?: return null
        val label = AgentActivityLabel.fromWire(resultSet.getString("label")) ?: return null
        AgentActivityStamp(recordedAt = recordedAt, label = label)
      }
    }
  }

  private fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
      Instant.parse(raw)
    } catch (_: DateTimeParseException) {
      null
    }
  }
}
