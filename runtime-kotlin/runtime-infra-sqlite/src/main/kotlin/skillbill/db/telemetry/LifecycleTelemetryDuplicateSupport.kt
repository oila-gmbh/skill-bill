package skillbill.db.telemetry

import java.sql.Connection

fun featureImplementAlreadyFinished(connection: Connection, sessionId: String): Boolean = connection.prepareStatement(
  """
    SELECT finished_at
    FROM feature_implement_sessions
    WHERE session_id = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(1, sessionId)
  statement.executeQuery().use { resultSet ->
    resultSet.next() && !resultSet.getString("finished_at").isNullOrBlank()
  }
}

fun incrementDuplicateFeatureImplementFinished(connection: Connection, sessionId: String) {
  connection.prepareStatement(
    """
    UPDATE feature_implement_sessions
    SET duplicate_terminal_finished_events = duplicate_terminal_finished_events + 1
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, sessionId)
    statement.executeUpdate()
  }
}

fun featureImplementStaleFinishedAlreadyEmitted(connection: Connection, sessionId: String): Boolean =
  connection.prepareStatement(
    """
    SELECT completion_status, finished_event_emitted_at
    FROM feature_implement_sessions
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, sessionId)
    statement.executeQuery().use { resultSet ->
      resultSet.next() &&
        resultSet.getString("completion_status") == "stale" &&
        !resultSet.getString("finished_event_emitted_at").isNullOrBlank()
    }
  }
