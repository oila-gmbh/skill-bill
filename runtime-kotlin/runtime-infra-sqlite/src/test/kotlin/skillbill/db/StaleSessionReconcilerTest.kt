package skillbill.db

import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.reconcileStaleFeatureImplementSessions
import skillbill.db.core.reconcileStaleFeatureTaskRuntimeSessions
import skillbill.db.core.reconcileStaleTelemetrySessions
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StaleSessionReconcilerTest {
  @Test
  fun `stale lifecycle sessions are closed and emit exactly one terminal event`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-test").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedStaleLifecycleSessions(connection)

      val reconciled = reconcileStaleTelemetrySessions(connection, level = "full")

      assertEquals(1, reconciled.featureImplementSessions)
      assertEquals(1, reconciled.featureTaskRuntimeSessions)
      assertEquals(1, reconciled.featureVerifySessions)
      assertEquals(1, reconciled.qualityCheckSessions)
      assertEquals(4, reconciled.emittedTerminalEvents)
      assertEquals("stale", columnValue(connection, "feature_implement_sessions", "fis-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "feature_task_runtime_sessions", "ftr-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "feature_verify_sessions", "fvs-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "quality_check_sessions", "qcs-stale", "result"))
      assertNotNull(columnValue(connection, "feature_verify_sessions", "fvs-stale", "finished_at"))
      assertEquals(1, eventCount(connection, "skillbill_feature_task_prose_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_verify_finished"))
      assertEquals(1, eventCount(connection, "skillbill_quality_check_finished"))
      assertEquals("stale", payload(connection, "skillbill_feature_verify_finished")["completion_status"])
      assertEquals("stale", payload(connection, "skillbill_quality_check_finished")["result"])

      val repeated = reconcileStaleTelemetrySessions(connection, level = "full")

      assertEquals(0, repeated.emittedTerminalEvents)
      assertEquals(1, eventCount(connection, "skillbill_feature_task_prose_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_verify_finished"))
      assertEquals(1, eventCount(connection, "skillbill_quality_check_finished"))
    }
  }

  @Test
  fun `late normal finish after stale reconciliation does not duplicate terminal events`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-late-finish").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedStaleLifecycleSessions(connection)
      reconcileStaleTelemetrySessions(connection, level = "full")

      val store = LifecycleTelemetryStore(connection)
      store.featureImplementFinished(featureImplementFinishedRecord("fis-stale"), level = "full")
      store.featureVerifyFinished(featureVerifyFinishedRecord("fvs-stale"), level = "full")
      store.qualityCheckFinished(qualityCheckFinishedRecord("qcs-stale"), level = "full")

      assertEquals(1, eventCount(connection, "skillbill_feature_task_prose_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_verify_finished"))
      assertEquals(1, eventCount(connection, "skillbill_quality_check_finished"))
      assertEquals("stale", columnValue(connection, "feature_implement_sessions", "fis-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "feature_verify_sessions", "fvs-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "quality_check_sessions", "qcs-stale", "result"))
      assertEquals(
        1,
        columnValue(connection, "feature_implement_sessions", "fis-stale", "duplicate_terminal_finished_events"),
      )
    }
  }

  @Test
  fun `old blocked goal issue is abandoned and emitted exactly once`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-goal").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_issue_progress (
            parent_workflow_id, issue_key, total_invocations, total_blocks,
            total_resumes, first_started_at, last_activity_at, last_blocked_at, mode
          ) VALUES (
            'goal-parent', 'SKILL-109', 2, 1, 1,
            datetime('now', '-20 days'), datetime('now', '-20 days'), datetime('now', '-20 days'), 'runtime'
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO goal_issue_progress (
            parent_workflow_id, issue_key, total_invocations, total_blocks,
            total_resumes, first_started_at, last_activity_at, last_blocked_at, mode
          ) VALUES (
            'goal-parent', 'SKILL-110', 1, 1, 0,
            datetime('now', '-20 days'), datetime('now', '-1 days'), datetime('now', '-20 days'), 'runtime'
          )
          """.trimIndent(),
        )
      }

      val reconciled = reconcileStaleTelemetrySessions(connection, level = "full", goalIssueAbandonmentDays = 14)

      assertEquals(1, reconciled.goalIssueAbandonedSessions)
      assertEquals(1, reconciled.emittedTerminalEvents)
      assertEquals(1, eventCount(connection, "skillbill_goal_issue_finished"))
      assertEquals("abandoned", payload(connection, "skillbill_goal_issue_finished")["status"])
      assertEquals("abandoned", goalColumnValue(connection, "SKILL-109", "status"))
      assertEquals(null, goalColumnValue(connection, "SKILL-110", "status"))

      val repeated = reconcileStaleTelemetrySessions(connection, level = "full", goalIssueAbandonmentDays = 14)

      assertEquals(0, repeated.goalIssueAbandonedSessions)
      assertEquals(1, eventCount(connection, "skillbill_goal_issue_finished"))
    }
  }

  @Test
  fun `legacy entry points still reconcile their lifecycle family`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-legacy-entry").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('fis-legacy', datetime('now', '-40000 seconds'), NULL, '')
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('ftr-legacy', datetime('now', '-40000 seconds'), NULL, '')
          """.trimIndent(),
        )
      }

      assertEquals(1, reconcileStaleFeatureImplementSessions(connection, thresholdSeconds = 28_800L))
      assertEquals(1, reconcileStaleFeatureTaskRuntimeSessions(connection, thresholdSeconds = 28_800L))
      assertEquals(1, eventCount(connection, "skillbill_feature_task_prose_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
    }
  }

  private fun seedStaleLifecycleSessions(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('fis-stale', datetime('now', '-40000 seconds'), NULL, '')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO feature_task_runtime_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('ftr-stale', datetime('now', '-40000 seconds'), NULL, '')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO feature_verify_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('fvs-stale', datetime('now', '-40000 seconds'), NULL, '')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO quality_check_sessions (session_id, started_at, finished_at, result)
        VALUES ('qcs-stale', datetime('now', '-40000 seconds'), NULL, NULL)
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('fis-fresh', datetime('now', '-1000 seconds'), NULL, '')
        """.trimIndent(),
      )
    }
  }

  private fun featureImplementFinishedRecord(sessionId: String): FeatureImplementFinishedRecord =
    FeatureImplementFinishedRecord(
      sessionId = sessionId,
      completionStatus = "completed",
      planCorrectionCount = 0,
      planTaskCount = 1,
      planPhaseCount = 1,
      featureFlagUsed = false,
      featureFlagPattern = "none",
      filesCreated = 0,
      filesModified = 1,
      tasksCompleted = 1,
      reviewIterations = 0,
      auditResult = "passed",
      auditIterations = 0,
      validationResult = "passed",
      boundaryHistoryWritten = false,
      boundaryHistoryValue = "none",
      prCreated = false,
      planDeviationNotes = "",
      childSteps = emptyList(),
    )

  private fun featureVerifyFinishedRecord(sessionId: String): FeatureVerifyFinishedRecord = FeatureVerifyFinishedRecord(
    sessionId = sessionId,
    featureFlagAuditPerformed = true,
    reviewIterations = 1,
    auditResult = "all_pass",
    completionStatus = "completed",
    historyRelevance = "medium",
    historyHelpfulness = "medium",
    gapsFound = emptyList(),
  )

  private fun qualityCheckFinishedRecord(sessionId: String): QualityCheckFinishedRecord = QualityCheckFinishedRecord(
    sessionId = sessionId,
    routedSkill = "bill-code-check",
    detectedStack = "kotlin",
    fallback = false,
    fallbackReason = null,
    scopeType = "branch_diff",
    initialFailureCount = 0,
    finalFailureCount = 0,
    iterations = 1,
    result = "pass",
    failingCheckNames = emptyList(),
    unsupportedReason = "",
  )

  private fun eventCount(connection: Connection, eventName: String): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM telemetry_outbox WHERE event_name = ?").use { statement ->
      statement.setString(1, eventName)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getInt(1)
      }
    }

  private fun columnValue(connection: Connection, tableName: String, sessionId: String, columnName: String): Any? =
    connection.prepareStatement("SELECT $columnName FROM $tableName WHERE session_id = ?").use { statement ->
      statement.setString(1, sessionId)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getObject(1)
      }
    }

  private fun goalColumnValue(connection: Connection, issueKey: String, columnName: String): Any? =
    connection.prepareStatement("SELECT $columnName FROM goal_issue_progress WHERE issue_key = ?").use { statement ->
      statement.setString(1, issueKey)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getObject(1)
      }
    }

  private fun payload(connection: Connection, eventName: String): Map<String, Any?> = connection.prepareStatement(
    """
      SELECT payload_json
      FROM telemetry_outbox
      WHERE event_name = ?
      ORDER BY id DESC
      LIMIT 1
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, eventName)
    statement.executeQuery().use { resultSet ->
      resultSet.next()
      val element = requireNotNull(JsonSupport.parseObjectOrNull(resultSet.getString("payload_json")))
      requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(element)))
    }
  }
}
