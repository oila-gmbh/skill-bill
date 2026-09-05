package skillbill.db.telemetry

import java.sql.Connection

fun lifecycleAlreadyFinished(connection: Connection, tableName: String, sessionId: String): Boolean =
  connection.prepareStatement("SELECT finished_at FROM $tableName WHERE session_id = ?").use { statement ->
    statement.setString(1, sessionId)
    statement.executeQuery().use { resultSet ->
      resultSet.next() && !resultSet.getString("finished_at").isNullOrBlank()
    }
  }

fun incrementDuplicateTerminalFinishedEvents(connection: Connection, tableName: String, sessionId: String) {
  connection.prepareStatement(
    """
    UPDATE $tableName
    SET duplicate_terminal_finished_events = duplicate_terminal_finished_events + 1
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, sessionId)
    statement.executeUpdate()
  }
}

fun staleFinishedAlreadyEmitted(
  connection: Connection,
  tableName: String,
  terminalColumn: String,
  sessionId: String,
): Boolean = connection.prepareStatement(
  """
    SELECT $terminalColumn, finished_event_emitted_at
    FROM $tableName
    WHERE session_id = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(1, sessionId)
  statement.executeQuery().use { resultSet ->
    resultSet.next() &&
      resultSet.getString(terminalColumn) == "stale" &&
      !resultSet.getString("finished_event_emitted_at").isNullOrBlank()
  }
}

fun terminalEventAlreadyEmitted(connection: Connection, tableName: String, sessionId: String): Boolean =
  connection.prepareStatement(
    """
    SELECT finished_event_emitted_at
    FROM $tableName
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, sessionId)
    statement.executeQuery().use { resultSet ->
      resultSet.next() && !resultSet.getString("finished_event_emitted_at").isNullOrBlank()
    }
  }
