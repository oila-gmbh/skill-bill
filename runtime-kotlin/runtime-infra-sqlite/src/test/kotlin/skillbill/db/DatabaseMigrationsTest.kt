package skillbill.db

import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseColumnMigrations
import skillbill.db.core.DatabaseMigrations
import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.DatabaseSchema
import skillbill.db.core.inImmediateTransaction
import skillbill.db.telemetry.GoalTelemetryMigration
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.worklist.SQLiteWorkListRepository
import skillbill.error.InvalidWorkListRowError
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class DatabaseMigrationsTest {
  @Test
  fun `immediate transaction rolls back non SQL failures and remains reusable`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-immediate-rollback").resolve("rollback.db")

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { it.execute("CREATE TABLE rollback_probe (value TEXT NOT NULL)") }

      assertFailsWith<IllegalStateException> {
        connection.inImmediateTransaction {
          createStatement().use { it.executeUpdate("INSERT INTO rollback_probe VALUES ('partial')") }
          error("non-SQL migration failure")
        }
      }

      assertEquals(0, rowCount(connection, "rollback_probe"))
      connection.inImmediateTransaction {
        createStatement().use { it.executeUpdate("INSERT INTO rollback_probe VALUES ('committed')") }
      }
      assertEquals(1, rowCount(connection, "rollback_probe"))
    }
  }

  @Test
  fun `migration definitions are append-only and deterministic`() {
    val migrationDefinitions = DatabaseMigrations.migrations.map { migration -> migration.version to migration.name }

    assertEquals(
      listOf(
        1 to "add-review-workflow-session-columns",
        2 to "normalize-feedback-event-outcomes",
        3 to "add-goal-telemetry-tables",
        4 to "add-work-list-state-metadata",
        5 to "recover-work-list-issue-keys",
        6 to "add-feature-task-execution-identities",
        7 to "add-feature-task-runtime-worker-leases",
        8 to "add-goal-planning-preparations",
        9 to "normalize-goal-planning-preparations",
        10 to "rebuild-goal-planning-plans-for-phase-output-0-2",
        11 to "require-goal-planning-phase-output-0-2",
        12 to "add-bounded-review-accounting",
      ),
      migrationDefinitions,
    )
    assertEquals(migrationDefinitions.sortedBy { (version, _) -> version }, migrationDefinitions)
    assertEquals(migrationDefinitions.map { (version, _) -> version }.toSet().size, migrationDefinitions.size)
    assertEquals(migrationDefinitions.map { (_, name) -> name }.toSet().size, migrationDefinitions.size)
  }

  @Test
  fun `migration v8 records and recreates goal planning preparations on an existing database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v8-goal-planning").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 8")
        statement.executeUpdate("DROP INDEX IF EXISTS idx_goal_planning_preparations_lookup")
        statement.executeUpdate("DROP TABLE IF EXISTS goal_planning_preparations")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "goal_planning_preparations")
      assertTrue("parent_goal_workflow_id" in columns)
      assertTrue("subtask_id" in columns)
      assertTrue("preparation_status" in columns)
      val migration = migrationRows(connection).singleOrNull { row ->
        row.version == 8 && row.name == "add-goal-planning-preparations"
      }
      assertNotNull(migration, "Migration version 8 add-goal-planning-preparations should be recorded.")
    }
  }

  @Test
  fun `migration v9 upgrades a v8 database with normalized planning tables`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v9-goal-planning").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 9")
        statement.executeUpdate("DROP TABLE goal_subtask_plans")
        statement.executeUpdate("DROP TABLE goal_shared_preplans")
      }
      assertTrue("parent_goal_workflow_id" in tableColumns(connection, "goal_planning_preparations"))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("payload_sha256" in tableColumns(connection, "goal_shared_preplans"))
      assertTrue("manifest_order" in tableColumns(connection, "goal_subtask_plans"))
      assertNotNull(
        migrationRows(connection).singleOrNull { row ->
          row.version == 9 && row.name == "normalize-goal-planning-preparations"
        },
      )
    }
  }

  @Test
  fun `ensureDatabase records all migrations for new databases`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("new.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val rows = migrationRows(connection)

      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version to migration.name },
        rows.map { row -> row.version to row.name },
      )
      rows.forEach { row -> assertTrue(row.appliedAt.isNotBlank()) }
    }
  }

  @Test
  fun `historical goal telemetry migration v3 remains unchanged while v4 owns work state metadata`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v3-contract").resolve("metrics.db")

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      GoalTelemetryMigration.apply(connection)
      val v3Columns = tableColumns(connection, "goal_issue_progress")

      assertFalse("state_entered_at" in v3Columns)
      assertFalse("state_entered_at_estimated" in v3Columns)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val v4Columns = tableColumns(connection, "goal_issue_progress")

      assertTrue("state_entered_at" in v4Columns)
      assertTrue("state_entered_at_estimated" in v4Columns)
      assertNotNull(migrationRows(connection).singleOrNull { it.version == 4 })
    }
  }

  @Test
  fun `ensureDatabase creates goal telemetry tables and records version 3`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("goal-telemetry.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val tables = tableColumns(connection = connection, tableName = "goal_run_sessions")
      val subtaskColumns = tableColumns(connection = connection, tableName = "goal_subtask_events")
      val issueColumns = tableColumns(connection = connection, tableName = "goal_issue_progress")

      assertTrue("workflow_id" in tables, "goal_run_sessions should be created with its workflow_id key.")
      assertTrue("subtask_id" in subtaskColumns, "goal_subtask_events should be created with its subtask_id column.")
      assertTrue("parent_workflow_id" in issueColumns, "goal_issue_progress should be created with its parent key.")
      assertTrue("last_activity_at" in issueColumns, "goal_issue_progress should track latest issue activity.")
      assertTrue("last_blocked_at" in issueColumns, "goal_issue_progress should track latest blocked segment.")
      assertNotNull(
        migrationRows(connection).singleOrNull { row -> row.version == 3 && row.name == "add-goal-telemetry-tables" },
        "Migration version 3 add-goal-telemetry-tables should be recorded.",
      )
    }
  }

  @Test
  fun `ensureDatabase does not duplicate recorded migrations on repeated opens`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("repeat.db")

    DatabaseRuntime.ensureDatabase(dbPath).close()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `concurrent migration opens serialize applicability checks`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-concurrent-migrations").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
    }
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val opens = (1..2).map {
        executor.submit {
          DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            ready.countDown()
            check(start.await(5, TimeUnit.SECONDS))
            DatabaseMigrations.apply(connection)
          }
        }
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      start.countDown()
      opens.forEach { it.get(10, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version },
        migrationRows(connection).map { row -> row.version },
      )
    }
  }

  @Test
  fun `reopening a healthy goal row does not rewrite healed state metadata`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-goal-healing").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_issue_progress (
            parent_workflow_id, issue_key, first_started_at, status, state_entered_at,
            state_entered_at_estimated
          ) VALUES ('goal-healthy', 'SKILL-117', '2026-05-01T12:00:00Z', 'running',
                    '2026-05-01T12:00:00Z', 0)
          """.trimIndent(),
        )
        statement.execute(
          """
          CREATE TRIGGER reject_healthy_goal_rewrite
          BEFORE UPDATE ON goal_issue_progress
          BEGIN
            SELECT RAISE(ABORT, 'healthy goal metadata must not be rewritten');
          END
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        "2026-05-01T12:00:00Z",
        tableColumnValue(
          connection = connection,
          tableName = "goal_issue_progress",
          pkColumnName = "parent_workflow_id",
          pkValue = "goal-healthy",
          columnName = "state_entered_at",
        ),
      )
    }
  }

  @Test
  fun `opening a legacy workflow with a partial state entry heal fills its missing estimated flag`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-partial-healing").resolve("metrics.db")
    seedPartiallyHealedLegacyWorkflow(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertPartiallyHealedStateEntry(connection)
    }
  }

  private fun seedPartiallyHealedLegacyWorkflow(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE feature_task_workflows (
            workflow_id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL DEFAULT '',
            workflow_name TEXT NOT NULL DEFAULT 'bill-feature-task',
            mode TEXT NOT NULL,
            implementation_skill TEXT NOT NULL DEFAULT '',
            contract_version TEXT NOT NULL,
            workflow_status TEXT NOT NULL DEFAULT 'pending',
            current_step_id TEXT NOT NULL DEFAULT '',
            steps_json TEXT NOT NULL DEFAULT '',
            artifacts_json TEXT NOT NULL DEFAULT '',
            issue_key TEXT,
            started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            state_entered_at TEXT,
            state_entered_at_estimated INTEGER,
            finished_at TEXT
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, started_at, state_entered_at,
            state_entered_at_estimated
          ) VALUES ('wfl-partial-heal', 'prose', '0.1', 'running', '2026-05-01T10:00:00Z',
                    '2026-05-02T11:00:00Z', NULL)
          """.trimIndent(),
        )
      }
    }
  }

  private fun assertPartiallyHealedStateEntry(connection: Connection) {
    assertEquals(
      "2026-05-02T11:00:00Z",
      tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-partial-heal", "state_entered_at"),
    )
    assertEquals(
      1,
      tableColumnValue(
        connection,
        "feature_task_workflows",
        "workflow_id",
        "wfl-partial-heal",
        "state_entered_at_estimated",
      ),
    )
  }

  @Test
  fun `legacy workflow and goal state entries use their documented timestamp fallbacks`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-state-entry-fallbacks").resolve("metrics.db")

    seedLegacyStateEntryFallbacks(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertLegacyStateEntryFallbacks(connection)
      assertMissingTimestampRowsRemainUnchanged(connection)
    }

    DatabaseRuntime.ensureDatabase(dbPath).close()
  }

  private fun seedLegacyStateEntryFallbacks(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        createLegacyStateEntryTables(statement)
        insertLegacyWorkflowStateEntryRows(statement)
        insertLegacyGoalStateEntryRows(statement)
      }
    }
  }

  private fun createLegacyStateEntryTables(statement: java.sql.Statement) {
    statement.execute(
      """
      CREATE TABLE feature_task_workflows (
        workflow_id TEXT PRIMARY KEY, session_id TEXT NOT NULL DEFAULT '',
        workflow_name TEXT NOT NULL DEFAULT 'bill-feature-task', mode TEXT NOT NULL,
        implementation_skill TEXT NOT NULL DEFAULT '', contract_version TEXT NOT NULL,
        workflow_status TEXT NOT NULL DEFAULT 'pending', current_step_id TEXT NOT NULL DEFAULT '',
        steps_json TEXT NOT NULL DEFAULT '', artifacts_json TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL, updated_at TEXT NOT NULL, finished_at TEXT
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE TABLE goal_issue_progress (
        parent_workflow_id TEXT NOT NULL, issue_key TEXT NOT NULL,
        total_invocations INTEGER NOT NULL DEFAULT 0, total_blocks INTEGER NOT NULL DEFAULT 0,
        total_resumes INTEGER NOT NULL DEFAULT 0, first_started_at TEXT, last_activity_at TEXT,
        last_blocked_at TEXT, latest_segment_workflow_id TEXT, last_blocked_segment_workflow_id TEXT,
        finished_at TEXT, status TEXT, subtasks_complete INTEGER, subtasks_blocked INTEGER,
        subtasks_skipped INTEGER, mode TEXT NOT NULL DEFAULT 'runtime', finished_event_emitted_at TEXT,
        PRIMARY KEY (parent_workflow_id, issue_key)
      )
      """.trimIndent(),
    )
  }

  private fun insertLegacyWorkflowStateEntryRows(statement: java.sql.Statement) {
    statement.executeUpdate(
      """
      INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, started_at, updated_at, finished_at)
      VALUES
        ('wfl-finished', 'prose', '0.1', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', '2026-05-03T10:00:00Z'),
        ('wfl-updated', 'prose', '0.1', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', NULL),
        ('wfl-started', 'prose', '0.1', '2026-05-01T10:00:00Z', '', NULL),
        ('wfl-no-time', 'prose', '0.1', '', '', NULL)
      """.trimIndent(),
    )
  }

  private fun insertLegacyGoalStateEntryRows(statement: java.sql.Statement) {
    statement.executeUpdate(
      """
      INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, last_activity_at, finished_at)
      VALUES
        ('goal-finished', 'SKILL-117', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', '2026-05-03T10:00:00Z'),
        ('goal-activity', 'SKILL-117', '2026-05-01T10:00:00Z', '2026-05-02T10:00:00Z', NULL),
        ('goal-started', 'SKILL-117', '2026-05-01T10:00:00Z', '', NULL),
        ('goal-no-time', 'SKILL-117', '', '', NULL)
      """.trimIndent(),
    )
  }

  private fun assertLegacyStateEntryFallbacks(connection: java.sql.Connection) {
    assertStateEntryFallbacks(
      connection,
      "feature_task_workflows",
      "workflow_id",
      "wfl",
      listOf("finished", "updated", "started"),
    )
    assertStateEntryFallbacks(
      connection,
      "goal_issue_progress",
      "parent_workflow_id",
      "goal",
      listOf("finished", "activity", "started"),
    )
    assertEstimatedMissingStateEntries(connection, "feature_task_workflows", "workflow_id", "wfl-no-time")
    assertEstimatedMissingStateEntries(connection, "goal_issue_progress", "parent_workflow_id", "goal-no-time")
    assertFailsWith<InvalidWorkListRowError> { SQLiteWorkListRepository(connection).list() }
  }

  private fun assertStateEntryFallbacks(
    connection: java.sql.Connection,
    table: String,
    keyColumn: String,
    prefix: String,
    suffixes: List<String>,
  ) {
    val expected = listOf("2026-05-03T10:00:00Z", "2026-05-02T10:00:00Z", "2026-05-01T10:00:00Z")
    suffixes.zip(expected).forEach { (suffix, timestamp) ->
      assertEquals(timestamp, tableColumnValue(connection, table, keyColumn, "$prefix-$suffix", "state_entered_at"))
    }
  }

  private fun assertEstimatedMissingStateEntries(
    connection: java.sql.Connection,
    table: String,
    keyColumn: String,
    rowKey: String,
  ) {
    assertEquals(null, nullableTableColumnValue(connection, table, keyColumn, rowKey, "state_entered_at"))
    assertEquals(1, tableColumnValue(connection, table, keyColumn, rowKey, "state_entered_at_estimated"))
  }

  private fun assertMissingTimestampRowsRemainUnchanged(connection: java.sql.Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TRIGGER reject_missing_timestamp_rewrite
        BEFORE UPDATE ON feature_task_workflows
        BEGIN
          SELECT RAISE(ABORT, 'missing legacy timestamp must not be rewritten');
        END
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `goal continuation recovery accepts the runtime and prose continuation contracts`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-goal-continuation-issue-key").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json, started_at, state_entered_at
          ) VALUES (
            'wftr-text-key', 'runtime', '0.1', 'running',
            '{"goal_continuation":{"issue_key":" SKILL-117 ","subtask_id":1,"suppress_pr":true,"goal_branch":"feature/117"}}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z'
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json, started_at, state_entered_at
          ) VALUES (
            'wfl-prose-key', 'prose', '0.1', 'running',
            '{"goal_continuation":{"enabled":true,"issue_key":"SKILL-118","subtask_id":2,"suppress_pr":true}}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z'
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json, started_at, state_entered_at
          ) VALUES (
            'wftr-number-key', 'runtime', '0.1', 'running',
            '{"goal_continuation":{"issue_key":117,"subtask_id":1,"suppress_pr":true,"goal_branch":"feature/117"}}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z'
          )
          """.trimIndent(),
        )
      }
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseColumnMigrations.applyWorkListMetadata(connection)
      assertEquals(
        "SKILL-117",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wftr-text-key", "issue_key"),
      )
      assertEquals(
        "SKILL-118",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-prose-key", "issue_key"),
      )
      assertEquals(
        null,
        nullableTableColumnValue(connection, "feature_task_workflows", "workflow_id", "wftr-number-key", "issue_key"),
      )
    }
  }

  @Test
  fun `migration after v4 recovers imported decomposition parent issue keys`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v5-issue-key-recovery").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 5")
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json,
            started_at, state_entered_at, state_entered_at_estimated
          ) VALUES (
            'wfl-imported-parent', 'prose', '0.1', 'abandoned',
            '{"decomposition_runtime":{"issue_key":" SKILL-117 ","status":"in_progress"}}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z', 0
          )
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        "SKILL-117",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-imported-parent", "issue_key"),
      )
      assertNotNull(migrationRows(connection).singleOrNull { row -> row.version == 5 })
    }
  }

  @Test
  fun `healthy database reopens do not retry unrecoverable issue key recovery`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-work-list-recovery").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, artifacts_json,
            started_at, state_entered_at, state_entered_at_estimated
          ) VALUES (
            'wftr-unrecoverable', 'runtime', '0.1', 'running', '{not json}',
            '2026-05-01T10:00:00Z', '2026-05-01T10:00:00Z', 0
          )
          """.trimIndent(),
        )
        statement.execute(
          """
          CREATE TRIGGER reject_recovery_retry
          BEFORE UPDATE ON feature_task_workflows
          BEGIN
            SELECT RAISE(ABORT, 'unrecoverable issue key must not be retried');
          END
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).close()
  }

  @Test
  fun `ensureDatabase adds missing review session column and backfills it`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-review-runs.db")
    createLegacyReviewRunsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "review_runs")
      val reviewSessionId = reviewSessionId(connection = connection, reviewRunId = "rvw-legacy-001")

      assertTrue("review_session_id" in columns)
      assertEquals("rvw-legacy-001", reviewSessionId)
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `review routed skill column is unconstrained text`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("review-routed-skill.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumnTypes(connection = connection, tableName = "review_runs")
      val schemaSql = reviewRunsSchemaSql(connection)

      assertEquals("TEXT", columns["routed_skill"])
      assertTrue("routed_skill TEXT" in schemaSql)
      assertTrue("routed_skill TEXT CHECK" !in schemaSql)
      assertTrue("routed_skill TEXT NOT NULL CHECK" !in schemaSql)
    }
  }

  @Test
  fun `ensureDatabase adds missing finding issue category column with default`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-findings.db")
    createLegacyFeedbackEventsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "findings")

      assertTrue("issue_category" in columns)
      assertEquals("other", findingIssueCategory(connection, "rvw-legacy-002", "F-001"))
    }
  }

  @Test
  fun `ensureDatabase creates feature implement telemetry health columns with defaults`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("feature-task-health.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_implement_sessions")
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-defaults')")
      }

      assertTrue("source" in columns)
      assertTrue("child_steps_json" in columns)
      assertTrue("duplicate_terminal_finished_events" in columns)
      assertEquals("production", featureImplementColumnValue(connection, "source"))
      assertEquals("", featureImplementColumnValue(connection, "child_steps_json"))
      assertEquals(0, featureImplementColumnValue(connection, "duplicate_terminal_finished_events"))
    }
  }

  @Test
  fun `ensureDatabase heals columns missing from a fully version-recorded legacy database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-recorded-implement.db")
    createLegacyFeatureImplementSessionsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_implement_sessions")
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-defaults')")
      }

      assertTrue("source" in columns, "source must be healed even when every migration version is already recorded.")
      assertEquals("production", featureImplementColumnValue(connection, "source"))
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `ensureDatabase heals legacy lifecycle starts before finished duration telemetry`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-lifecycle-starts.db")
    createLegacyLifecycleSessionsWithoutStartsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("started_at" in tableColumns(connection, "feature_implement_sessions"))
      assertTrue("started_at" in tableColumns(connection, "feature_verify_sessions"))
      assertTrue("started_at" in tableColumns(connection, "quality_check_sessions"))
      assertTrue("fallback" in tableColumns(connection, "quality_check_sessions"))
      assertTrue("fallback_reason" in tableColumns(connection, "quality_check_sessions"))
      assertEquals(
        0,
        tableColumnValue(
          connection = connection,
          tableName = "quality_check_sessions",
          pkColumnName = "session_id",
          pkValue = "qcs-legacy-start",
          columnName = "fallback",
        ),
      )
      assertEquals(
        LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT,
        tableColumnValue(
          connection = connection,
          tableName = "feature_implement_sessions",
          pkColumnName = "session_id",
          pkValue = "fis-legacy-duration",
          columnName = "started_at",
        ),
        "Feature implement started_at must be recovered from the matching legacy workflow row.",
      )

      LifecycleTelemetryStore(connection).featureImplementFinished(featureImplementFinishedRecord(), level = "full")

      val payload = outboxPayload(connection, "skillbill_feature_task_prose_finished")
      assertTrue(
        (payload["duration_seconds"] as Number).toLong() > 0,
        "Finished feature implement telemetry must compute non-zero duration from the healed started_at.",
      )
    }
  }

  @Test
  fun `ensureDatabase adds token estimation columns to feature_task_runtime_sessions`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-ftr-tokens.db")
    createLegacyFeatureTaskRuntimeSessionsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_task_runtime_sessions")

      assertTrue("estimated_phase_tokens_json" in columns, "estimated_phase_tokens_json must be healed.")
      assertTrue("estimated_total_tokens" in columns, "estimated_total_tokens must be healed.")
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_task_runtime_sessions",
          "session_id",
          "ftr-pre-91",
          "estimated_phase_tokens_json",
        ),
        "Pre-feature row must read null for estimated_phase_tokens_json.",
      )
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_task_runtime_sessions",
          "session_id",
          "ftr-pre-91",
          "estimated_total_tokens",
        ),
        "Pre-feature row must read null for estimated_total_tokens.",
      )
    }
  }

  @Test
  fun `ensureDatabase adds token estimation columns to feature_implement_sessions`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-fis-tokens.db")
    createLegacyFeatureImplementSessionsDatabase(dbPath)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-pre-91')")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "feature_implement_sessions")

      assertTrue("estimated_phase_tokens_json" in columns, "estimated_phase_tokens_json must be healed.")
      assertTrue("estimated_total_tokens" in columns, "estimated_total_tokens must be healed.")
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_implement_sessions",
          "session_id",
          "fis-pre-91",
          "estimated_phase_tokens_json",
        ),
        "Pre-feature row must read null for estimated_phase_tokens_json.",
      )
      assertEquals(
        null,
        nullableTableColumnValue(
          connection,
          "feature_implement_sessions",
          "session_id",
          "fis-pre-91",
          "estimated_total_tokens",
        ),
        "Pre-feature row must read null for estimated_total_tokens.",
      )
    }
  }

  @Test
  fun `ensureDatabase heals goal subtask agent attribution columns on a fully version-recorded legacy database`() {
    // SKILL-89: a DB created before the agent-attribution columns existed already records migration
    // version 3, so editing the applied migration body is a silent no-op. The unconditional column
    // ensure must heal the two columns on every startup.
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-goal-subtask.db")
    createLegacyGoalSubtaskEventsDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "goal_subtask_events")

      assertTrue("finalizing_agent_id" in columns, "finalizing_agent_id must be healed onto a legacy table.")
      assertTrue("participating_agent_ids" in columns, "participating_agent_ids must be healed onto a legacy table.")
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_subtask_events (
            issue_key, workflow_id, subtask_id, subtask_name, status,
            started_at, finished_at, duration_ms, attempt_count
          ) VALUES ('SKILL-89', 'wf-legacy', 1, 'heal', 'complete', 't0', 't1', 1000, 1)
          """.trimIndent(),
        )
      }
      assertEquals("[]", goalSubtaskColumnValue(connection, "participating_agent_ids"))
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `ensureDatabase creates goal issue progress without altering legacy goal rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-goal-issue.db")
    createLegacyGoalSubtaskEventsDatabase(dbPath)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO goal_subtask_events (
            issue_key, workflow_id, subtask_id, subtask_name, status,
            started_at, finished_at, duration_ms, attempt_count
          ) VALUES ('SKILL-109', 'wf-existing', 1, 'implement', 'blocked', 't0', 't1', 1000, 2)
          """.trimIndent(),
        )
      }
    }
    val before = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      legacyGoalSubtaskRows(connection)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val columns = tableColumns(connection = connection, tableName = "goal_issue_progress")
      val after = legacyGoalSubtaskRows(connection)

      assertTrue("parent_workflow_id" in columns, "goal_issue_progress must exist after startup.")
      assertTrue("last_activity_at" in columns, "goal_issue_progress must heal last_activity_at after startup.")
      assertTrue("last_blocked_at" in columns, "goal_issue_progress must heal last_blocked_at after startup.")
      assertTrue("latest_segment_workflow_id" in columns)
      assertTrue("last_blocked_segment_workflow_id" in columns)
      assertEquals(before, after, "Adding goal_issue_progress must not rewrite existing subtask rows.")
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
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  @Test
  fun `ensureDatabase resumes from recorded migration version`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("partial.db")
    createLegacyFeedbackEventsDatabase(dbPath)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement(
        """
        INSERT INTO schema_migrations (version, name)
        VALUES (?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setInt(1, 1)
        statement.setString(2, "add-review-workflow-session-columns")
        statement.executeUpdate()
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals("fix_rejected", feedbackEventType(connection, "rvw-legacy-002", "F-001"))
      assertNotNull(migrationRows(connection).singleOrNull { row -> row.version == 2 })
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

  private fun createLegacyFeatureImplementSessionsDatabase(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_SQL)
        statement.execute(
          """
          CREATE TABLE schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement(
        """
        INSERT INTO schema_migrations (version, name)
        VALUES (?, ?)
        """.trimIndent(),
      ).use { statement ->
        DatabaseMigrations.migrations.forEach { migration ->
          statement.setInt(1, migration.version)
          statement.setString(2, migration.name)
          statement.executeUpdate()
        }
      }
    }
  }

  private fun createLegacyFeatureTaskRuntimeSessionsDatabase(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(CREATE_LEGACY_FEATURE_TASK_RUNTIME_SESSIONS_SQL)
        statement.execute(
          """
          CREATE TABLE schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement("INSERT INTO schema_migrations (version, name) VALUES (?, ?)").use { statement ->
        DatabaseMigrations.migrations.forEach { migration ->
          statement.setInt(1, migration.version)
          statement.setString(2, migration.name)
          statement.executeUpdate()
        }
      }
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_task_runtime_sessions (session_id) VALUES ('ftr-pre-91')")
      }
    }
  }

  private fun createLegacyLifecycleSessionsWithoutStartsDatabase(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_WITHOUT_START_SQL)
        statement.execute(CREATE_LEGACY_FEATURE_TASK_WORKFLOWS_SQL)
        statement.execute(CREATE_LEGACY_FEATURE_VERIFY_SESSIONS_WITHOUT_START_SQL)
        statement.execute(CREATE_LEGACY_QUALITY_CHECK_SESSIONS_WITHOUT_START_SQL)
        statement.execute(
          """
          CREATE TABLE schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement("INSERT INTO schema_migrations (version, name) VALUES (?, ?)").use { statement ->
        DatabaseMigrations.migrations.forEach { migration ->
          statement.setInt(1, migration.version)
          statement.setString(2, migration.name)
          statement.executeUpdate()
        }
      }
      connection.createStatement().use { statement ->
        statement.executeUpdate("INSERT INTO feature_implement_sessions (session_id) VALUES ('fis-legacy-duration')")
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, session_id, mode, implementation_skill, contract_version,
            workflow_status, current_step_id, steps_json, artifacts_json, started_at, updated_at
          ) VALUES (
            'wf-legacy-duration', 'fis-legacy-duration', 'runtime', 'bill-feature-task-runtime', '0.1',
            'running', 'implement', '[]', '{}', '$LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT',
            '$LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT'
          )
          """.trimIndent(),
        )
        statement.executeUpdate("INSERT INTO feature_verify_sessions (session_id) VALUES ('fvs-legacy-start')")
        statement.executeUpdate("INSERT INTO quality_check_sessions (session_id) VALUES ('qcs-legacy-start')")
      }
    }
  }

  private fun createLegacyGoalSubtaskEventsDatabase(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(CREATE_LEGACY_GOAL_SUBTASK_EVENTS_SQL)
        statement.execute(
          """
          CREATE TABLE schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement("INSERT INTO schema_migrations (version, name) VALUES (?, ?)").use { statement ->
        DatabaseMigrations.migrations.forEach { migration ->
          statement.setInt(1, migration.version)
          statement.setString(2, migration.name)
          statement.executeUpdate()
        }
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

  private fun reviewRunsSchemaSql(connection: java.sql.Connection): String = connection.prepareStatement(
    """
      SELECT sql
      FROM sqlite_master
      WHERE type = 'table' AND name = 'review_runs'
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

  private fun findingIssueCategory(connection: java.sql.Connection, reviewRunId: String, findingId: String): String =
    connection.prepareStatement(
      """
      SELECT issue_category
      FROM findings
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

  private fun featureImplementColumnValue(connection: java.sql.Connection, columnName: String): Any =
    connection.prepareStatement(
      """
      SELECT $columnName
      FROM feature_implement_sessions
      WHERE session_id = 'fis-defaults'
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getObject(1)
      }
    }

  private fun migrationRows(connection: java.sql.Connection): List<MigrationRow> = connection.prepareStatement(
    """
      SELECT version, name, applied_at
      FROM schema_migrations
      ORDER BY version
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            MigrationRow(
              version = resultSet.getInt("version"),
              name = resultSet.getString("name"),
              appliedAt = resultSet.getString("applied_at"),
            ),
          )
        }
      }
    }
  }

  private fun goalSubtaskColumnValue(connection: java.sql.Connection, columnName: String): Any =
    connection.prepareStatement("SELECT $columnName FROM goal_subtask_events LIMIT 1").use { statement ->
      statement.executeQuery().use { resultSet ->
        check(resultSet.next()) { "Expected a seeded goal_subtask_events row." }
        resultSet.getObject(1)
      }
    }

  private fun nullableTableColumnValue(
    connection: java.sql.Connection,
    tableName: String,
    pkColumnName: String,
    pkValue: String,
    columnName: String,
  ): Any? = connection.prepareStatement(
    "SELECT $columnName FROM $tableName WHERE $pkColumnName = ?",
  ).use { statement ->
    statement.setString(1, pkValue)
    statement.executeQuery().use { resultSet ->
      check(resultSet.next()) { "Expected a row with $pkColumnName = '$pkValue' in $tableName." }
      resultSet.getObject(1)
    }
  }

  private fun tableColumnValue(
    connection: java.sql.Connection,
    tableName: String,
    pkColumnName: String,
    pkValue: String,
    columnName: String,
  ): Any? = connection.prepareStatement(
    "SELECT $columnName FROM $tableName WHERE $pkColumnName = ?",
  ).use { statement ->
    statement.setString(1, pkValue)
    statement.executeQuery().use { resultSet ->
      check(resultSet.next()) { "Expected a row with $pkColumnName = '$pkValue' in $tableName." }
      resultSet.getObject(1)
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

  private fun tableColumnTypes(connection: java.sql.Connection, tableName: String): Map<String, String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
        buildMap {
          while (resultSet.next()) {
            put(resultSet.getString("name"), resultSet.getString("type"))
          }
        }
      }
    }

  private fun legacyGoalSubtaskRows(connection: java.sql.Connection): List<Map<String, Any?>> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        """
        SELECT issue_key, workflow_id, subtask_id, subtask_name, status,
               started_at, finished_at, duration_ms, attempt_count, blocked_reason,
               subtask_event_emitted_at
        FROM goal_subtask_events
        ORDER BY issue_key, workflow_id, subtask_id
        """.trimIndent(),
      ).use { resultSet ->
        val metadata = resultSet.metaData
        buildList {
          while (resultSet.next()) {
            add(
              buildMap {
                for (index in 1..metadata.columnCount) {
                  put(metadata.getColumnName(index), resultSet.getObject(index))
                }
              },
            )
          }
        }
      }
    }

  private fun featureImplementFinishedRecord(): FeatureImplementFinishedRecord = FeatureImplementFinishedRecord(
    sessionId = "fis-legacy-duration",
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

  private fun outboxPayload(connection: java.sql.Connection, eventName: String): Map<String, Any?> =
    connection.prepareStatement(
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
        check(resultSet.next()) { "Expected telemetry outbox event '$eventName'." }
        val element = requireNotNull(JsonSupport.parseObjectOrNull(resultSet.getString("payload_json")))
        requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(element)))
      }
    }

  private fun rowCount(connection: Connection, tableName: String): Int = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { resultSet ->
      check(resultSet.next())
      resultSet.getInt(1)
    }
  }

  private data class MigrationRow(
    val version: Int,
    val name: String,
    val appliedAt: String,
  )

  private companion object {
    const val CREATE_LEGACY_FEATURE_TASK_RUNTIME_SESSIONS_SQL: String =
      """
      CREATE TABLE feature_task_runtime_sessions (
        session_id TEXT PRIMARY KEY,
        feature_size TEXT NOT NULL DEFAULT 'MEDIUM',
        issue_key TEXT NOT NULL DEFAULT '',
        feature_name TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        completion_status TEXT NOT NULL DEFAULT '',
        completed_phase_ids TEXT NOT NULL DEFAULT '',
        phase_outcomes TEXT NOT NULL DEFAULT '',
        last_incomplete_phase TEXT NOT NULL DEFAULT '',
        blocked_reason TEXT NOT NULL DEFAULT '',
        resolved_branch TEXT NOT NULL DEFAULT '',
        review_fix_iteration_count INTEGER NOT NULL DEFAULT 0,
        audit_gap_iteration_count INTEGER NOT NULL DEFAULT 0,
        finished_at TEXT
      )
      """

    const val CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_SQL: String =
      """
      CREATE TABLE feature_implement_sessions (
        session_id TEXT PRIMARY KEY,
        completion_status TEXT,
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """

    const val CREATE_LEGACY_FEATURE_IMPLEMENT_SESSIONS_WITHOUT_START_SQL: String =
      """
      CREATE TABLE feature_implement_sessions (
        session_id TEXT PRIMARY KEY,
        completion_status TEXT
      )
      """

    const val CREATE_LEGACY_FEATURE_TASK_WORKFLOWS_SQL: String =
      """
      CREATE TABLE feature_task_workflows (
        workflow_id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL DEFAULT '',
        workflow_name TEXT NOT NULL DEFAULT 'bill-feature-task',
        mode TEXT NOT NULL,
        implementation_skill TEXT NOT NULL DEFAULT '',
        contract_version TEXT NOT NULL,
        workflow_status TEXT NOT NULL DEFAULT 'pending',
        current_step_id TEXT NOT NULL DEFAULT '',
        steps_json TEXT NOT NULL DEFAULT '',
        artifacts_json TEXT NOT NULL DEFAULT '',
        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        finished_at TEXT
      )
      """

    const val LEGACY_FEATURE_TASK_WORKFLOW_STARTED_AT: String = "2026-06-04 10:00:00"

    const val CREATE_LEGACY_FEATURE_VERIFY_SESSIONS_WITHOUT_START_SQL: String =
      """
      CREATE TABLE feature_verify_sessions (
        session_id TEXT PRIMARY KEY
      )
      """

    const val CREATE_LEGACY_QUALITY_CHECK_SESSIONS_WITHOUT_START_SQL: String =
      """
      CREATE TABLE quality_check_sessions (
        session_id TEXT PRIMARY KEY
      )
      """

    const val CREATE_LEGACY_GOAL_SUBTASK_EVENTS_SQL: String =
      """
      CREATE TABLE goal_subtask_events (
        issue_key TEXT NOT NULL,
        workflow_id TEXT NOT NULL,
        subtask_id INTEGER NOT NULL,
        subtask_name TEXT NOT NULL DEFAULT '',
        status TEXT NOT NULL,
        started_at TEXT NOT NULL,
        finished_at TEXT NOT NULL,
        duration_ms INTEGER NOT NULL,
        attempt_count INTEGER NOT NULL,
        blocked_reason TEXT,
        subtask_event_emitted_at TEXT,
        PRIMARY KEY (issue_key, subtask_id, workflow_id)
      )
      """

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
