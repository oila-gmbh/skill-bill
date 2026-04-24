package skillbill.db

import java.sql.Connection

data class TelemetryOutboxRow(
  val id: Long,
  val eventName: String,
  val payloadJson: String,
  val createdAt: String,
  val syncedAt: String?,
  val lastError: String,
)

class TelemetryOutboxStore(
  private val connection: Connection,
) {
  fun enqueue(eventName: String, payloadJson: String): Long {
    connection.prepareStatement(
      """
      INSERT INTO telemetry_outbox (event_name, payload_json)
      VALUES (?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, eventName)
      statement.setString(2, payloadJson)
      statement.executeUpdate()
    }
    return connection.createStatement().use { statement ->
      statement.executeQuery("SELECT last_insert_rowid()").use { resultSet ->
        resultSet.next()
        resultSet.getLong(1)
      }
    }
  }

  fun listPending(limit: Int? = null): List<TelemetryOutboxRow> {
    val sql =
      buildString {
        appendLine("SELECT id, event_name, payload_json, created_at, synced_at, last_error")
        appendLine("FROM telemetry_outbox")
        appendLine("WHERE synced_at IS NULL")
        appendLine("ORDER BY id")
        if (limit != null) {
          append("LIMIT ?")
        }
      }.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
      if (limit != null) {
        statement.setInt(1, limit)
      }
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(
              TelemetryOutboxRow(
                id = resultSet.getLong("id"),
                eventName = resultSet.getString("event_name"),
                payloadJson = resultSet.getString("payload_json"),
                createdAt = resultSet.getString("created_at"),
                syncedAt = resultSet.getString("synced_at"),
                lastError = resultSet.getString("last_error").orEmpty(),
              ),
            )
          }
        }
      }
    }
  }

  fun pendingCount(): Int = connection.prepareStatement(
    """
      SELECT COUNT(*)
      FROM telemetry_outbox
      WHERE synced_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getInt(1)
    }
  }

  fun latestError(): String? = connection.prepareStatement(
    """
      SELECT last_error
      FROM telemetry_outbox
      WHERE synced_at IS NULL AND last_error != ''
      ORDER BY id DESC
      LIMIT 1
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      if (!resultSet.next()) {
        return null
      }
      resultSet.getString("last_error").orEmpty().ifBlank { null }
    }
  }

  fun markSynced(id: Long, syncedAt: String) {
    connection.prepareStatement(
      """
      UPDATE telemetry_outbox
      SET synced_at = ?, last_error = ''
      WHERE id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, syncedAt)
      statement.setLong(2, id)
      statement.executeUpdate()
    }
  }

  fun markSynced(eventIds: List<Long>) {
    if (eventIds.isEmpty()) {
      return
    }
    val placeholders = eventIds.joinToString(", ") { "?" }
    connection.prepareStatement(
      """
      UPDATE telemetry_outbox
      SET synced_at = CURRENT_TIMESTAMP, last_error = ''
      WHERE id IN ($placeholders)
      """.trimIndent(),
    ).use { statement ->
      eventIds.forEachIndexed { index, eventId ->
        statement.setLong(index + 1, eventId)
      }
      statement.executeUpdate()
    }
  }

  fun markFailed(id: Long, lastError: String) {
    connection.prepareStatement(
      """
      UPDATE telemetry_outbox
      SET last_error = ?
      WHERE id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, lastError)
      statement.setLong(2, id)
      statement.executeUpdate()
    }
  }

  fun markFailed(eventIds: List<Long>, lastError: String) {
    if (eventIds.isEmpty()) {
      return
    }
    val placeholders = eventIds.joinToString(", ") { "?" }
    connection.prepareStatement(
      """
      UPDATE telemetry_outbox
      SET last_error = ?
      WHERE id IN ($placeholders)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, lastError)
      eventIds.forEachIndexed { index, eventId ->
        statement.setLong(index + 2, eventId)
      }
      statement.executeUpdate()
    }
  }
}
