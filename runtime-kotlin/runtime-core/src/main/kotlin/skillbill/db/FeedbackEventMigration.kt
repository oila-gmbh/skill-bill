package skillbill.db

import java.sql.Connection

internal object FeedbackEventMigration {
  private val legacyFeedbackEventTypes: Set<String> = setOf("accepted", "dismissed", "fix_requested")

  private const val FEEDBACK_EVENT_ID_INDEX: Int = 1
  private const val FEEDBACK_EVENT_REVIEW_RUN_ID_INDEX: Int = 2
  private const val FEEDBACK_EVENT_FINDING_ID_INDEX: Int = 3
  private const val FEEDBACK_EVENT_TYPE_INDEX: Int = 4
  private const val FEEDBACK_EVENT_NOTE_INDEX: Int = 5
  private const val FEEDBACK_EVENT_CREATED_AT_INDEX: Int = 6

  fun apply(connection: Connection) {
    val createSql = feedbackEventsCreateSql(connection) ?: return
    if (!feedbackEventsNeedMigration(createSql)) {
      return
    }
    val rows = legacyFeedbackEventRows(connection)
    connection.renameFeedbackEventsTable()
    connection.createCurrentFeedbackEventsTable()
    connection.insertFeedbackEventRows(rows)
    connection.createStatement().use { statement ->
      statement.execute("DROP TABLE feedback_events_legacy")
    }
  }

  private fun feedbackEventsCreateSql(connection: Connection): String? = connection.prepareStatement(
    """
      SELECT sql
      FROM sqlite_master
      WHERE type = 'table' AND name = 'feedback_events'
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      if (resultSet.next()) {
        resultSet.getString("sql").orEmpty()
      } else {
        null
      }
    }
  }

  private fun feedbackEventsNeedMigration(createSql: String): Boolean {
    val hasCurrentSchema = DbConstants.findingOutcomeTypes.all { eventType -> "'$eventType'" in createSql }
    val hasLegacySchema = legacyFeedbackEventTypes.any { eventType -> "'$eventType'" in createSql }
    return !(hasCurrentSchema && !hasLegacySchema)
  }

  private fun legacyFeedbackEventRows(connection: Connection): List<LegacyFeedbackEventRow> =
    connection.prepareStatement(
      """
      SELECT id, review_run_id, finding_id, event_type, note, created_at
      FROM feedback_events
      ORDER BY id
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(
              LegacyFeedbackEventRow(
                id = resultSet.getLong("id"),
                reviewRunId = resultSet.getString("review_run_id"),
                findingId = resultSet.getString("finding_id"),
                eventType = normalizeFeedbackEventType(resultSet.getString("event_type")),
                note = resultSet.getString("note").orEmpty(),
                createdAt = resultSet.getString("created_at"),
              ),
            )
          }
        }
      }
    }

  private fun normalizeFeedbackEventType(eventType: String): String {
    val normalizedEventType =
      when (eventType) {
        "accepted" -> "finding_accepted"
        "dismissed" -> "fix_rejected"
        "fix_requested" -> "fix_applied"
        else -> eventType
      }
    require(normalizedEventType in DbConstants.findingOutcomeTypes) {
      "Unsupported finding outcome '$eventType'."
    }
    return normalizedEventType
  }

  private fun Connection.renameFeedbackEventsTable() {
    createStatement().use { statement ->
      statement.execute("ALTER TABLE feedback_events RENAME TO feedback_events_legacy")
    }
  }

  private fun Connection.createCurrentFeedbackEventsTable() {
    createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE feedback_events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          review_run_id TEXT NOT NULL,
          finding_id TEXT NOT NULL,
          event_type TEXT NOT NULL CHECK (
            event_type IN ('finding_accepted', 'fix_applied', 'finding_edited', 'fix_rejected', 'false_positive')
          ),
          note TEXT NOT NULL DEFAULT '',
          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
        )
        """.trimIndent(),
      )
    }
    createStatement().use { statement ->
      statement.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_feedback_events_run
          ON feedback_events(review_run_id, finding_id, id)
        """.trimIndent(),
      )
    }
  }

  private fun Connection.insertFeedbackEventRows(rows: List<LegacyFeedbackEventRow>) {
    prepareStatement(
      """
      INSERT INTO feedback_events (id, review_run_id, finding_id, event_type, note, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      rows.forEach { row ->
        statement.setLong(FEEDBACK_EVENT_ID_INDEX, row.id)
        statement.setString(FEEDBACK_EVENT_REVIEW_RUN_ID_INDEX, row.reviewRunId)
        statement.setString(FEEDBACK_EVENT_FINDING_ID_INDEX, row.findingId)
        statement.setString(FEEDBACK_EVENT_TYPE_INDEX, row.eventType)
        statement.setString(FEEDBACK_EVENT_NOTE_INDEX, row.note)
        statement.setString(FEEDBACK_EVENT_CREATED_AT_INDEX, row.createdAt)
        statement.addBatch()
      }
      if (rows.isNotEmpty()) {
        statement.executeBatch()
      }
    }
  }

  private data class LegacyFeedbackEventRow(
    val id: Long,
    val reviewRunId: String,
    val findingId: String,
    val eventType: String,
    val note: String,
    val createdAt: String,
  )
}
