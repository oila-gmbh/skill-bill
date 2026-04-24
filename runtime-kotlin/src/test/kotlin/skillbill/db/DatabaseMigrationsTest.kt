package skillbill.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseMigrationsTest {
  @Test
  fun `ensureDatabase adds missing review session column and backfills it`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-review-runs.db")
    createLegacyReviewRunsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "review_runs")
      val reviewSessionId = reviewSessionId(connection = connection, reviewRunId = "rvw-legacy-001")

      assertTrue("review_session_id" in columns)
      assertEquals("rvw-legacy-001", reviewSessionId)
    }
  }

  @Test
  fun `ensureDatabase migrates legacy feedback event values to current schema`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-feedback-events.db")
    createLegacyFeedbackEventsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val schemaSql = feedbackEventsSchemaSql(connection)
      val migratedEventType =
        feedbackEventType(connection = connection, reviewRunId = "rvw-legacy-002", findingId = "F-001")

      assertTrue("'fix_rejected'" in schemaSql)
      assertEquals("fix_rejected", migratedEventType)
    }
  }

  private fun createLegacyReviewRunsDatabase(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(CREATE_LEGACY_REVIEW_RUNS_SQL)
      }
      connection.prepareStatement(
        """
        INSERT INTO review_runs (review_run_id, routed_skill, raw_text)
        VALUES (?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "rvw-legacy-001")
        statement.setString(2, "bill-kotlin-code-review")
        statement.setString(3, "legacy review")
        statement.executeUpdate()
      }
    }
  }

  private fun createLegacyFeedbackEventsDatabase(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("PRAGMA foreign_keys = ON")
        statement.execute(CREATE_LEGACY_VERIFY_REVIEW_RUNS_SQL)
        statement.execute(CREATE_LEGACY_FINDINGS_SQL)
        statement.execute(CREATE_LEGACY_FEEDBACK_EVENTS_SQL)
      }
      connection.prepareStatement(
        """
        INSERT INTO review_runs (review_run_id, raw_text)
        VALUES (?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "rvw-legacy-002")
        statement.setString(2, "legacy review")
        statement.executeUpdate()
      }
      connection.prepareStatement(
        """
        INSERT INTO findings (review_run_id, finding_id, severity, confidence, location, description, finding_text)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "rvw-legacy-002")
        statement.setString(2, "F-001")
        statement.setString(3, "Major")
        statement.setString(4, "High")
        statement.setString(5, "README.md:1")
        statement.setString(6, "legacy finding")
        statement.setString(7, "legacy finding text")
        statement.executeUpdate()
      }
      connection.prepareStatement(
        """
        INSERT INTO feedback_events (review_run_id, finding_id, event_type, note, created_at)
        VALUES (?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "rvw-legacy-002")
        statement.setString(2, "F-001")
        statement.setString(3, "dismissed")
        statement.setString(4, "legacy note")
        statement.setString(5, "2026-04-23 00:00:00")
        statement.executeUpdate()
      }
    }
  }

  private fun reviewSessionId(connection: java.sql.Connection, reviewRunId: String): String =
    connection.prepareStatement(
      """
      SELECT review_session_id
      FROM review_runs
      WHERE review_run_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, reviewRunId)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getString(1)
      }
    }

  private fun feedbackEventsSchemaSql(connection: java.sql.Connection): String = connection.prepareStatement(
    """
      SELECT sql
      FROM sqlite_master
      WHERE type = 'table' AND name = 'feedback_events'
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      resultSet.getString(1)
    }
  }

  private fun feedbackEventType(connection: java.sql.Connection, reviewRunId: String, findingId: String): String =
    connection.prepareStatement(
      """
      SELECT event_type
      FROM feedback_events
      WHERE review_run_id = ? AND finding_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, reviewRunId)
      statement.setString(2, findingId)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getString(1)
      }
    }

  private fun tableColumns(connection: java.sql.Connection, tableName: String): Set<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
      }
    }

  private companion object {
    const val CREATE_LEGACY_REVIEW_RUNS_SQL: String =
      """
      CREATE TABLE review_runs (
        review_run_id TEXT PRIMARY KEY,
        routed_skill TEXT,
        raw_text TEXT NOT NULL,
        imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """

    const val CREATE_LEGACY_VERIFY_REVIEW_RUNS_SQL: String =
      """
      CREATE TABLE review_runs (
        review_run_id TEXT PRIMARY KEY,
        raw_text TEXT NOT NULL,
        imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """

    const val CREATE_LEGACY_FINDINGS_SQL: String =
      """
      CREATE TABLE findings (
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        severity TEXT NOT NULL,
        confidence TEXT NOT NULL,
        location TEXT NOT NULL,
        description TEXT NOT NULL,
        finding_text TEXT NOT NULL,
        PRIMARY KEY (review_run_id, finding_id),
        FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
      )
      """

    const val CREATE_LEGACY_FEEDBACK_EVENTS_SQL: String =
      """
      CREATE TABLE feedback_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        event_type TEXT NOT NULL CHECK (
          event_type IN ('accepted', 'dismissed', 'fix_requested')
        ),
        note TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (review_run_id, finding_id) REFERENCES findings(review_run_id, finding_id) ON DELETE CASCADE
      )
      """
  }
}
