package skillbill.db

import org.junit.jupiter.api.Assumptions
import skillbill.db.core.DatabaseColumnMigrations
import skillbill.db.core.DatabaseMigrations
import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.DatabaseSchema
import skillbill.db.core.inImmediateTransaction
import skillbill.db.telemetry.GoalTelemetryMigration
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.db.worklist.SQLiteWorkListRepository
import skillbill.error.InvalidWorkListRowError
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        13 to "allow-goal-planning-phase-output-0-3",
        14 to "add-rejected-output-diagnostics",
        15 to "add-private-producer-output-evidence",
        16 to "rekey-producer-output-evidence-by-generation",
        17 to "persist-goal-planning-repair-evidence",
        18 to "persist-legacy-goal-planning-repair-evidence",
        19 to "add-goal-runner-controls",
        20 to "add-goal-runner-control-state",
        21 to "add-delegated-review-lifecycle-projection",
        22 to "drop-delegated-review-lifecycle-tables",
        23 to "add-feature-task-runtime-audit-generations",
        24 to "backfill-review-attribution-canonicals",
        25 to "add-review-run-lane-attribution",
        26 to "relax-telemetry-outbox-last-error",
        27 to "add-review-finding-outcome-key",
        28 to "rekey-producer-output-evidence-by-agent",
      ),
      migrationDefinitions,
    )
    assertEquals(migrationDefinitions.sortedBy { (version, _) -> version }, migrationDefinitions)
    assertEquals(migrationDefinitions.map { (version, _) -> version }.toSet().size, migrationDefinitions.size)
    assertEquals(migrationDefinitions.map { (_, name) -> name }.toSet().size, migrationDefinitions.size)
  }

  @Test
  fun `a fresh database carries no delegated review lifecycle tables`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v22-fresh").resolve("fresh.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertFalse(tableExists(connection, "review_lifecycle_events"))
      assertFalse(tableExists(connection, "review_delegated_lifecycle"))
      assertNotNull(
        migrationRows(connection).singleOrNull { row ->
          row.version == 22 && row.name == "drop-delegated-review-lifecycle-tables"
        },
        "Migration version 22 drop-delegated-review-lifecycle-tables should be recorded.",
      )
    }
  }

  @Test
  fun `migration v22 drops the lifecycle tables an existing database still carries`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v22-existing").resolve("legacy.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 22")
        statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS review_lifecycle_events (review_id TEXT NOT NULL, payload TEXT NOT NULL)",
        )
        statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS review_delegated_lifecycle (review_id TEXT PRIMARY KEY, payload TEXT NOT NULL)",
        )
        statement.executeUpdate(
          "CREATE INDEX IF NOT EXISTS idx_review_lifecycle_events_review ON review_lifecycle_events(review_id)",
        )
        statement.executeUpdate(
          "CREATE INDEX IF NOT EXISTS idx_review_delegated_lifecycle_review " +
            "ON review_delegated_lifecycle(review_id)",
        )
        statement.executeUpdate("INSERT INTO review_lifecycle_events VALUES ('rvw-1', '{}')")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertFalse(tableExists(connection, "review_lifecycle_events"))
      assertFalse(tableExists(connection, "review_delegated_lifecycle"))
    }
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
  fun `a version recorded under a foreign name does not skip this build's migration`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-version-collision").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DROP TABLE schema_migrations")
        statement.executeUpdate(VERSION_KEYED_SCHEMA_MIGRATIONS_SQL)
        DatabaseMigrations.migrations.forEach { migration ->
          val name = if (migration.version == 16) "add-feature-task-convergence-state" else migration.name
          statement.executeUpdate(
            "INSERT INTO schema_migrations (version, name) VALUES (${migration.version}, '$name')",
          )
        }
        statement.executeUpdate("DROP TABLE producer_output_evidence")
        statement.executeUpdate(PRE_GENERATION_PRODUCER_OUTPUT_EVIDENCE_SQL)
      }

      DatabaseMigrations.apply(connection)

      assertTrue(
        columnNames(connection, "producer_output_evidence").contains("generation"),
        "The branch-numbered ledger row must not mask this build's version 16 migration.",
      )
      val names = migrationRows(connection).map { row -> row.name }
      assertTrue(
        names.contains("add-feature-task-convergence-state"),
        "The already-applied foreign migration must stay recorded.",
      )
      assertTrue(
        names.contains("rekey-producer-output-evidence-by-generation"),
        "This build's version 16 migration must be recorded once it runs.",
      )
    }
  }

  @Test
  fun `applying twice against a name keyed ledger is a no-op`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-idempotent-apply").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val before = migrationRows(connection).map { row -> row.name }

      DatabaseMigrations.apply(connection)

      assertEquals(before, migrationRows(connection).map { row -> row.name })
      assertTrue(columnNames(connection, "producer_output_evidence").contains("generation"))
    }
  }

  @Test
  fun `an empty database with no ledger table migrates through the gated path`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-empty").resolve("empty.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertFalse(tableExists(connection, "schema_migrations"), "The seeded database must start with no ledger.")
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version to migration.name },
        migrationRows(connection).map { row -> row.version to row.name },
      )
    }
  }

  @Test
  fun `a version keyed ledger is rebuilt under the write lock`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-version-keyed").resolve("metrics.db")
    seedVersionKeyedLedger(dbPath)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { migrator ->
          migrator.createStatement().use { it.execute("PRAGMA busy_timeout = 0") }
          assertFailsWith<SQLException>(
            "A version-keyed ledger must take the write lock so ensureNameKeyed runs inside it.",
          ) { DatabaseMigrations.apply(migrator) }
        }
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseMigrations.apply(connection)
      assertFalse(
        versionIsPrimaryKey(connection),
        "ensureNameKeyed must rebuild the ledger as name-keyed once it holds the write lock.",
      )
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.name },
        migrationRows(connection).map { row -> row.name },
        "The rebuild must carry every recorded migration name across.",
      )
    }
  }

  @Test
  fun `an already current database opens no write transaction while another connection holds the writer lock`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-current").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    val expected = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      migrationRows(connection).map { row -> row.name }
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { reader ->
          // busy_timeout = 0 turns any attempt to take the write lock into an immediate failure, so a
          // passing run proves the gate skipped the transaction instead of waiting out a timeout.
          reader.createStatement().use { it.execute("PRAGMA busy_timeout = 0") }
          val startedAt = System.nanoTime()
          DatabaseMigrations.apply(reader)
          val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
          assertTrue(elapsedMillis < 1_000, "A no-op apply must return promptly, took ${elapsedMillis}ms.")
          assertEquals(expected, migrationRows(reader).map { row -> row.name })
        }
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }
  }

  @Test
  fun `racing applies re-derive in lock and apply each pending migration exactly once`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-race").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      // Version 13 renames and rebuilds the planning tables, so a second application against an
      // already-rebuilt schema fails loudly. Both racers see it pending; only one may run it.
      connection.createStatement().use { it.executeUpdate("DELETE FROM schema_migrations WHERE version = 13") }
    }
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val races = (1..2).map {
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
      races.forEach { it.get(10, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertEquals(
        DatabaseMigrations.migrations.map { migration -> migration.version to migration.name },
        migrationRows(connection).map { row -> row.version to row.name },
        "The migration that lost the race must be re-derived away, not applied twice.",
      )
      assertTrue("payload_sha256" in tableColumns(connection, "goal_subtask_plans"))
    }
  }

  @Test
  fun `column heal still runs on a write capable open with no pending migration`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-gate-column-heal").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      // Drop a column an already-applied migration body appends today: the ledger stays complete and
      // name-keyed, so DatabaseMigrations.apply short-circuits and only the column heal can restore it.
      connection.createStatement().use {
        it.executeUpdate("ALTER TABLE feature_task_workflows DROP COLUMN interruption_reason")
      }
      assertFalse("interruption_reason" in tableColumns(connection, "feature_task_workflows"))
      assertFalse(versionIsPrimaryKey(connection), "The ledger must already be name-keyed for this to gate.")
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
      connection.createStatement().use {
        // state_entered_at is NOT NULL in the base schema, so an unset value is the empty string the
        // heal's COALESCE treats as missing; it must fall back to started_at.
        it.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, mode, contract_version, workflow_status, started_at, updated_at, state_entered_at
          ) VALUES ('wfl-gate-heal', 'prose', '0.1', 'running', '2026-05-01T10:00:00Z', '', '')
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(
        "interruption_reason" in tableColumns(connection, "feature_task_workflows"),
        "DatabaseColumnMigrations.apply must heal the column even though no migration is pending.",
      )
      assertEquals(
        "2026-05-01T10:00:00Z",
        tableColumnValue(connection, "feature_task_workflows", "workflow_id", "wfl-gate-heal", "state_entered_at"),
        "healWorkListMetadata must keep running on a write-capable open with nothing pending.",
      )
    }
  }

  private fun seedVersionKeyedLedger(dbPath: Path) {
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DROP TABLE schema_migrations")
        statement.executeUpdate(VERSION_KEYED_SCHEMA_MIGRATIONS_SQL)
        DatabaseMigrations.migrations.forEach { migration ->
          statement.executeUpdate(
            "INSERT INTO schema_migrations (version, name) VALUES (${migration.version}, '${migration.name}')",
          )
        }
      }
    }
  }

  @Test
  fun `migration v16 rekeys producer output evidence without losing a row`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v16-producer-evidence").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 16")
        statement.executeUpdate(
          "DELETE FROM schema_migrations WHERE name = 'rekey-producer-output-evidence-by-agent'",
        )
        statement.executeUpdate("DROP TABLE producer_output_evidence")
        statement.executeUpdate(PRE_GENERATION_PRODUCER_OUTPUT_EVIDENCE_SQL)
      }
      connection.prepareStatement(
        """
        INSERT INTO producer_output_evidence
        (workflow_id, phase_id, attempt, agent_id, model, recorded_at, byte_size, sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        listOf(1 to byteArrayOf(7, 8), 2 to null).forEach { (attempt, payload) ->
          statement.setString(1, "wfl-legacy")
          statement.setString(2, "review")
          statement.setInt(3, attempt)
          statement.setString(4, "codex")
          statement.setString(5, "gpt")
          statement.setString(6, "2026-07-28T10:00:0${attempt}Z")
          statement.setLong(7, payload?.size?.toLong() ?: 0L)
          statement.setString(8, attempt.toString().repeat(64))
          statement.setBytes(9, payload)
          statement.executeUpdate()
        }
      }
    }

    val migratedDdl = DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertNotNull(
        migrationRows(connection).singleOrNull { row ->
          row.version == 16 && row.name == "rekey-producer-output-evidence-by-generation"
        },
      )
      val rows = producerEvidenceRows(connection)
      assertEquals(2, rows.size)
      assertEquals(listOf(0, 0), rows.map { it.generation })
      assertEquals(listOf(1, 2), rows.map { it.attempt })
      assertEquals(listOf("2026-07-28T10:00:01Z", "2026-07-28T10:00:02Z"), rows.map { it.recordedAt })
      assertEquals(listOf("1".repeat(64), "2".repeat(64)), rows.map { it.sha256 })
      assertContentEquals(byteArrayOf(7, 8), rows[0].payload)
      assertEquals(null, rows[1].payload)
      producerEvidenceDdl(connection)
    }

    val baseSchemaPath = Files.createTempDirectory("runtime-kotlin-db-v16-base-schema").resolve("base.db")
    DriverManager.getConnection("jdbc:sqlite:$baseSchemaPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      assertEquals(
        producerEvidenceDdl(connection),
        migratedDdl,
        "the migration DDL and the base-schema DDL must not drift",
      )
    }
  }

  @Test
  fun `migration v28 rekeys producer output evidence by agent without losing a row`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-v28-producer-evidence").resolve("metrics.db")
    val payload = "skill-15-review-0-2".encodeToByteArray()
    val sha = "8a5dfb56fd3d".padEnd(64, '0')

    seedPreAgentProducerEvidenceForMigration28(dbPath, payload, sha)

    val migratedDdl = DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertMigration28Applied(connection)
      assertProducerEvidenceRowSurvivedMigration28(connection, payload, sha)
      producerEvidenceDdl(connection)
    }

    assertProducerEvidenceMigrationDdlParity(migratedDdl, "runtime-kotlin-db-v28-base-schema")
  }

  private fun seedPreAgentProducerEvidenceForMigration28(dbPath: Path, payload: ByteArray, sha: String) {
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "DELETE FROM schema_migrations WHERE name = 'rekey-producer-output-evidence-by-agent'",
        )
        statement.executeUpdate("DROP TABLE producer_output_evidence")
        statement.executeUpdate(PRE_AGENT_PRODUCER_OUTPUT_EVIDENCE_SQL)
      }
      connection.prepareStatement(
        """
        INSERT INTO producer_output_evidence
        (workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at, byte_size, sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "wftr-20260808-175505-c5po")
        statement.setString(2, "review")
        statement.setInt(3, 0)
        statement.setInt(4, 2)
        statement.setString(5, "claude")
        statement.setString(6, "claude-opus")
        statement.setString(7, "2026-08-08T18:49:48Z")
        statement.setLong(8, payload.size.toLong())
        statement.setString(9, sha)
        statement.setBytes(10, payload)
        statement.executeUpdate()
      }
    }
  }

  private fun assertMigration28Applied(connection: Connection) {
    assertNotNull(
      migrationRows(connection).singleOrNull { row ->
        row.version == 28 && row.name == "rekey-producer-output-evidence-by-agent"
      },
    )
  }

  private fun assertProducerEvidenceRowSurvivedMigration28(connection: Connection, payload: ByteArray, sha: String) {
    connection.prepareStatement(
      """
      SELECT generation, attempt, agent_id, recorded_at, sha256, payload
      FROM producer_output_evidence
      WHERE workflow_id = ? AND phase_id = ? AND generation = ? AND attempt = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, "wftr-20260808-175505-c5po")
      statement.setString(2, "review")
      statement.setInt(3, 0)
      statement.setInt(4, 2)
      statement.executeQuery().use { rows ->
        check(rows.next()) { "SKILL-15-shaped producer evidence row must survive migration 28." }
        assertEquals(0, rows.getInt("generation"))
        assertEquals(2, rows.getInt("attempt"))
        assertEquals("claude", rows.getString("agent_id"))
        assertEquals("2026-08-08T18:49:48Z", rows.getString("recorded_at"))
        assertEquals(sha, rows.getString("sha256"))
        assertContentEquals(payload, rows.getBytes("payload"))
        assertFalse(rows.next())
      }
    }
  }

  private fun assertProducerEvidenceMigrationDdlParity(migratedDdl: String, baseSchemaDirPrefix: String) {
    val baseSchemaPath = Files.createTempDirectory(baseSchemaDirPrefix).resolve("base.db")
    DriverManager.getConnection("jdbc:sqlite:$baseSchemaPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      assertEquals(
        producerEvidenceDdl(connection),
        migratedDdl,
        "the migration DDL and the base-schema DDL must not drift",
      )
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

  // SKILL-136 subtask 5 AC-007: lane attribution is additive. A store that predates it gains the
  // table and the finding columns without losing a single recorded review row.
  @Test
  fun `ensureDatabase adds review run lane attribution to a legacy store without losing rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-migrations").resolve("legacy-review-lanes.db")
    createLegacyFeedbackEventsDatabase(dbPath)
    val before = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      Triple(rowCount(connection, "review_runs"), rowCount(connection, "findings"), findingRows(connection))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue("review_run_lanes" in tableNames(connection))
      assertTrue("review_run_finding_lanes" in tableNames(connection))
      assertTrue(
        tableColumns(connection, "findings").containsAll(setOf("lane_skill_name", "lane_area", "lane_pack_slug")),
      )
      assertEquals(before.first, rowCount(connection, "review_runs"))
      assertEquals(before.second, rowCount(connection, "findings"))
      assertEquals(before.third, findingRows(connection), "Existing finding rows must survive unchanged.")
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(before.first, rowCount(connection, "review_runs"), "Re-applying migrations must be a no-op.")
      assertEquals(before.second, rowCount(connection, "findings"))
      assertEquals(before.third, findingRows(connection))
      assertEquals(DatabaseMigrations.migrations.size, migrationRows(connection).size)
    }
  }

  // SKILL-136 subtask 6 AC-001/AC-008: the outbox rebuild backfills '' to NULL, preserves genuine
  // error text verbatim, and never drops a row.
  @Test
  fun `relaxing telemetry outbox last_error backfills empty strings and preserves error text`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outbox-migration").resolve("legacy-outbox.db")
    createLegacyTelemetryOutboxDatabase(dbPath)
    val before = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      rowCount(connection, "telemetry_outbox")
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(before, rowCount(connection, "telemetry_outbox"), "The rebuild must not drop a row.")
      assertEquals(
        0,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE last_error = ''"),
        "Every legacy empty-string last_error must be backfilled to NULL.",
      )
      assertEquals(
        2,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE last_error IS NULL"),
        "Both healthy rows must read as NULL.",
      )
      assertEquals(
        "boom",
        scalarString(connection, "SELECT last_error FROM telemetry_outbox WHERE id = 2"),
        "A genuine delivery failure must survive verbatim.",
      )
      assertFalse(
        tableInfo(connection, "telemetry_outbox").single { it.name == "last_error" }.notNull,
        "last_error must be nullable after the rebuild.",
      )
      assertTrue("idx_telemetry_outbox_pending" in tableIndexNames(connection))
    }
  }

  // SKILL-163 AC-002/AC-005: an existing store gains skill_bill_version on startup, and every row
  // that predates it survives with a NULL version rather than being dropped or backfilled.
  @Test
  fun `opening a legacy telemetry outbox adds skill_bill_version and preserves version-less rows`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outbox-version").resolve("legacy-outbox.db")
    createLegacyTelemetryOutboxDatabase(dbPath)
    val before = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      rowCount(connection, "telemetry_outbox")
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(
        "skill_bill_version" in tableColumns(connection, "telemetry_outbox"),
        "An existing store must gain the column, not only a freshly created one.",
      )
      assertEquals(before, rowCount(connection, "telemetry_outbox"), "No pre-migration row may be dropped.")
      assertEquals(
        before,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE skill_bill_version IS NULL"),
        "Pre-migration rows must stay version-absent rather than being given a fabricated version.",
      )
    }
  }

  @Test
  fun `re-applying the telemetry outbox relaxation is a no-op with a single ledger entry`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outbox-idempotent").resolve("legacy-outbox.db")
    createLegacyTelemetryOutboxDatabase(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).close()
    val afterFirst = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      telemetryOutboxRows(connection)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(afterFirst, telemetryOutboxRows(connection), "Re-application must not alter a single row.")
      assertEquals(
        1,
        migrationRows(connection).count { it.name == "relax-telemetry-outbox-last-error" },
        "The ledger must record the relaxation exactly once.",
      )
    }
  }

  // AC-003/AC-008: the key columns arrive through ensureColumn, so pre-existing ledger rows survive
  // and are marked unresolved rather than being defaulted to a guessed review run.
  @Test
  fun `adding the review finding outcome key preserves existing ledger rows as unresolved`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-outcome-migration").resolve("legacy-ledger.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE unaddressed_findings (
            issue_key TEXT NOT NULL, workflow_id TEXT NOT NULL, subtask_id INTEGER NOT NULL,
            review_pass_number INTEGER NOT NULL, finding_ordinal INTEGER NOT NULL,
            severity TEXT NOT NULL, issue_category TEXT NOT NULL DEFAULT 'other',
            location TEXT NOT NULL, summary TEXT NOT NULL,
            recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (workflow_id, review_pass_number, finding_ordinal)
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO unaddressed_findings
            (issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
             severity, location, summary)
          VALUES ('SKILL-1', 'wf-legacy', 1, 1, 1, 'blocker', 'a.kt:1', 'pre-existing')
          """.trimIndent(),
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(1, rowCount(connection, "unaddressed_findings"), "The pre-existing row must survive.")
      assertTrue(tableColumns(connection, "unaddressed_findings").containsAll(setOf("review_run_id", "finding_id")))
      assertEquals(
        1,
        scalarInt(
          connection,
          "SELECT COUNT(*) FROM unaddressed_findings WHERE review_run_id IS NULL AND finding_id IS NULL",
        ),
        "A row that predates the key must stay unresolved, never bucketed to a guessed review run.",
      )
      assertTrue("review_finding_outcomes" in tableNames(connection))
    }
  }

  /**
   * AC-007's real-store check. It needs a real ~91.5 MB review-metrics store, which is far too large
   * to commit, so it is env-gated on SKILL_BILL_REAL_STORE_DB and reports as skipped when unset. The
   * store is copied first: migrations never run against the operator's live database.
   */
  @Test
  fun `migrating a copy of a real review metrics store preserves every table row count`() {
    val realStore = requireRealStore()
    val copy = Files.createTempDirectory("runtime-kotlin-real-store").resolve("metrics.db")
    Files.copy(realStore, copy)

    val before = DriverManager.getConnection("jdbc:sqlite:$copy").use(::allTableRowCounts)
    DatabaseRuntime.ensureDatabase(copy).close()
    val after = DriverManager.getConnection("jdbc:sqlite:$copy").use(::allTableRowCounts)

    // schema_migrations is the ledger of what has been applied, so it gains one row for every
    // migration the store was behind on. Every table that carries data must survive untouched.
    before.filterKeys { it != SCHEMA_MIGRATIONS_TABLE }.forEach { (table, count) ->
      assertEquals(count, after[table], "Migration must preserve every row of '$table'.")
    }
    assertTrue(
      (after[SCHEMA_MIGRATIONS_TABLE] ?: 0) >= (before[SCHEMA_MIGRATIONS_TABLE] ?: 0),
      "Migration must never drop an applied-migration record.",
    )
  }

  /**
   * SKILL-136 subtask 6 AC-008/AC-009. The row-count harness above proves nothing is lost; this one
   * proves the migrated data is *correct* at real volume — the outbox backfill actually landed, no
   * finding was orphaned, and the outbox still drains. Same env gate and same copy-first discipline:
   * the operator's live store is never migrated, mutated, or deleted.
   */
  @Test
  fun `migrating a copy of a real review metrics store leaves referential integrity sound`() {
    val realStore = requireRealStore()
    val copy = Files.createTempDirectory("runtime-kotlin-real-store-integrity").resolve("metrics.db")
    Files.copy(realStore, copy)

    val trackedTables = listOf(
      "telemetry_outbox",
      "review_runs",
      "findings",
      "feedback_events",
      "learnings",
      "session_learnings",
      "unaddressed_findings",
    )
    val before = DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
      val present = tableNames(connection)
      trackedTables.filter(present::contains).associateWith { table -> rowCount(connection, table) }
    }

    DatabaseRuntime.ensureDatabase(copy).use { connection ->
      before.forEach { (table, count) ->
        assertEquals(count, rowCount(connection, table), "Migration must preserve every row of '$table'.")
      }
      assertEquals(
        0,
        scalarInt(connection, "SELECT COUNT(*) FROM telemetry_outbox WHERE last_error = ''"),
        "AC-001: no empty-string last_error may survive the backfill.",
      )
      assertEquals(
        0,
        scalarInt(
          connection,
          "SELECT COUNT(*) FROM findings f " +
            "WHERE NOT EXISTS (SELECT 1 FROM review_runs r WHERE r.review_run_id = f.review_run_id)",
        ),
        "AC-009: no finding may be left without its review_runs parent.",
      )

      // AC-009: the outbox still drains fully with the nullable column in place.
      val store = TelemetryOutboxStore(connection)
      val pendingIds = store.listPending(null).map(TelemetryOutboxRecord::id)
      store.markSynced(pendingIds)
      assertTrue(store.listPending(null).isEmpty(), "The outbox must drain fully after marking every row synced.")
      assertEquals(null, store.latestError(), "A fully drained outbox must report no delivery error.")
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

  // SKILL-136 subtask 4 AC-004/AC-006: a legacy review_runs table gains the canonical columns on
  // open, existing raw values are preserved untouched, and the unambiguous-only backfill collapses
  // the observed prose variants to one row per pack and one row per stack.
  @Test
  fun `legacy review runs gain canonical attribution columns and an unambiguous backfill`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-canonical-backfill").resolve("metrics.db")
    val expectedRowCount = seedLegacyReviewRunAttributionVariants(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(
        columnNames(connection, "review_runs").containsAll(
          setOf(
            "routed_skill_canonical",
            "detected_stack_canonical",
            "detected_scope_canonical",
            "detected_scope_detail",
          ),
        ),
      )
      assertEquals(expectedRowCount, rowCount(connection, "review_runs"))

      // The 6 routed-skill and 5 stack prose variants collapse; only the deliberately ambiguous row
      // stays behind, and it stays as the explicit unresolved marker rather than being bucketed.
      assertEquals(
        mapOf("bill-kmp-code-review" to 11, "unresolved" to 1),
        groupCount(connection, "routed_skill_canonical"),
      )
      assertEquals(mapOf("kotlin" to 11, "unresolved" to 1), groupCount(connection, "detected_stack_canonical"))
      assertEquals(
        mapOf("commit_range" to 6, "pull_request" to 5, "unresolved" to 1),
        groupCount(connection, "detected_scope_canonical"),
      )
      assertEquals("main..HEAD", reviewRunColumn(connection, "rvw-skill-0", "detected_scope_detail"))

      // Raw text is never rewritten by the backfill.
      LEGACY_ROUTED_SKILL_VARIANTS.forEachIndexed { index, routedSkill ->
        assertEquals(routedSkill, reviewRunColumn(connection, "rvw-skill-$index", "routed_skill"))
      }
      assertEquals(AMBIGUOUS_ROUTED_SKILL, reviewRunColumn(connection, "rvw-ambiguous", "routed_skill"))
      assertEquals("unresolved", reviewRunColumn(connection, "rvw-ambiguous", "execution_mode"))
    }
  }

  // SKILL-136 subtask 4 AC-004: re-opening the store re-runs nothing — the collapsed cardinality and
  // the retained raw text stay exactly as the first open left them.
  @Test
  fun `the review attribution backfill is idempotent across repeated opens`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-canonical-reopen").resolve("metrics.db")
    val expectedRowCount = seedLegacyReviewRunAttributionVariants(dbPath)

    DatabaseRuntime.ensureDatabase(dbPath).close()

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(expectedRowCount, rowCount(connection, "review_runs"))
      assertEquals(
        mapOf("bill-kmp-code-review" to 11, "unresolved" to 1),
        groupCount(connection, "routed_skill_canonical"),
      )
      assertEquals(mapOf("kotlin" to 11, "unresolved" to 1), groupCount(connection, "detected_stack_canonical"))
      assertEquals(AMBIGUOUS_ROUTED_SKILL, reviewRunColumn(connection, "rvw-ambiguous", "routed_skill"))
    }
  }

  // Seeds a legacy review_runs table carrying the observed prose variants and returns the row count.
  private fun seedLegacyReviewRunAttributionVariants(dbPath: Path): Int {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE review_runs (
            review_run_id TEXT PRIMARY KEY,
            routed_skill TEXT,
            detected_scope TEXT,
            detected_stack TEXT,
            execution_mode TEXT,
            raw_text TEXT NOT NULL
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement(
        "INSERT INTO review_runs (review_run_id, routed_skill, detected_scope, detected_stack, raw_text) " +
          "VALUES (?, ?, ?, ?, 'raw')",
      ).use { statement ->
        LEGACY_ROUTED_SKILL_VARIANTS.forEachIndexed { index, routedSkill ->
          statement.setString(1, "rvw-skill-$index")
          statement.setString(2, routedSkill)
          statement.setString(3, "commit range (main..HEAD)")
          statement.setString(4, "kotlin")
          statement.addBatch()
        }
        LEGACY_STACK_VARIANTS.forEachIndexed { index, stack ->
          statement.setString(1, "rvw-stack-$index")
          statement.setString(2, "bill-kmp-code-review")
          statement.setString(3, "pull request (#204)")
          statement.setString(4, stack)
          statement.addBatch()
        }
        statement.setString(1, "rvw-ambiguous")
        statement.setString(2, AMBIGUOUS_ROUTED_SKILL)
        statement.setString(3, "whatever the agent felt like")
        statement.setString(4, "kotlin, ios")
        statement.addBatch()
        statement.executeBatch()
      }
    }
    return LEGACY_ROUTED_SKILL_VARIANTS.size + LEGACY_STACK_VARIANTS.size + 1
  }

  // SKILL-136 subtask 4 AC-002/AC-006: the backfill is a one-shot ledger migration that never
  // overwrites an ingestion-computed canonical and converges instead of rewriting rows on every open.
  @Test
  fun `review attribution backfill runs once and never overwrites ingestion canonicals`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-review-canonical-converge").resolve("metrics.db")

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE review_runs (
            review_run_id TEXT PRIMARY KEY,
            routed_skill TEXT,
            detected_scope TEXT,
            detected_stack TEXT,
            execution_mode TEXT,
            raw_text TEXT NOT NULL
          )
          """.trimIndent(),
        )
        statement.execute(
          "INSERT INTO review_runs (review_run_id, routed_skill, detected_scope, detected_stack, raw_text) " +
            "VALUES ('rvw-unresolvable', 'bill-kmp-code-review, bill-ios-code-review', " +
            "'whatever the agent felt like', 'kotlin, ios', 'raw')",
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals("unresolved", reviewRunColumn(connection, "rvw-unresolvable", "routed_skill_canonical"))
      // Stand in for a value ingestion resolved against the discovered pack catalog: the backfill's own
      // vocabulary would not produce it, so re-running must leave it alone.
      connection.createStatement().use { statement ->
        statement.execute(
          "UPDATE review_runs SET routed_skill_canonical = 'bill-acme-code-review' " +
            "WHERE review_run_id = 'rvw-unresolvable'",
        )
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals("bill-acme-code-review", reviewRunColumn(connection, "rvw-unresolvable", "routed_skill_canonical"))
      assertEquals(
        1,
        connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT COUNT(*) FROM schema_migrations WHERE name = 'backfill-review-attribution-canonicals'",
          ).use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        },
        "The canonical backfill must be recorded once as a one-shot ledger migration.",
      )
    }
  }

  // SKILL-136 subtask 4 AC-006: run against a COPY of a real review-metrics store by exporting
  // SKILL_BILL_MIGRATION_FIXTURE_DB. Unset (the CI default) the test skips so the suite stays hermetic.
  @Test
  fun `migrating a copy of a real review metrics store preserves every row`() {
    val source = requireGatedStore(MIGRATION_FIXTURE_ENV)
    val copy = Files.createTempDirectory("runtime-kotlin-real-store-migration").resolve("metrics.db")
    Files.copy(source, copy)

    val before = DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
      rowCount(connection, "review_runs") to rowCount(connection, "findings")
    }

    DatabaseRuntime.ensureDatabase(copy).use { connection ->
      assertEquals(before.first, rowCount(connection, "review_runs"), "review_runs lost rows during migration.")
      assertEquals(before.second, rowCount(connection, "findings"), "findings lost rows during migration.")

      val routedSkills = groupCount(connection, "routed_skill_canonical")
      val stacks = groupCount(connection, "detected_stack_canonical")
      assertTrue(routedSkills.size < before.first, "Canonical routed skills must collapse the raw variants.")
      assertTrue(stacks.size < before.first, "Canonical stacks must collapse the raw variants.")
      assertEquals(0, executionModeGaps(connection), "Every run must carry an execution_mode after migration.")
    }
  }

  /**
   * Resolves an opt-in real-store gate. An unset gate aborts the test as *skipped* rather than
   * returning green: a harness that never ran must never look like one that passed. A set gate that
   * does not resolve to a file is a hard failure, so a typo cannot silently disable the harness.
   */
  private fun requireGatedStore(gate: String): Path {
    val configured = System.getenv(gate)?.takeIf { it.isNotBlank() }
    Assumptions.assumeTrue(
      configured != null,
      "$gate is unset; point it at a review-metrics.db copy to run this harness.",
    )
    val source = Path.of(configured)
    assertTrue(Files.isRegularFile(source), "$gate must point at an existing database file, but was '$configured'.")
    return source
  }

  private fun requireRealStore(): Path = requireGatedStore(REAL_STORE_ENV)

  private fun groupCount(connection: Connection, column: String): Map<String, Int> =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT $column, COUNT(*) FROM review_runs GROUP BY $column").use { resultSet ->
        buildMap {
          while (resultSet.next()) {
            put(resultSet.getString(1), resultSet.getInt(2))
          }
        }
      }
    }

  private fun reviewRunColumn(connection: Connection, reviewRunId: String, column: String): String? =
    connection.prepareStatement("SELECT $column FROM review_runs WHERE review_run_id = ?").use { statement ->
      statement.setString(1, reviewRunId)
      statement.executeQuery().use { resultSet ->
        check(resultSet.next())
        resultSet.getString(1)
      }
    }

  private fun executionModeGaps(connection: Connection): Int = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT COUNT(*) FROM review_runs WHERE execution_mode IS NULL OR execution_mode = ''")
      .use { resultSet ->
        check(resultSet.next())
        resultSet.getInt(1)
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

  private fun columnNames(connection: java.sql.Connection, table: String): Set<String> =
    connection.prepareStatement("SELECT name FROM pragma_table_info(?)").use { statement ->
      statement.setString(1, table)
      statement.executeQuery().use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
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

  private fun producerEvidenceDdl(connection: java.sql.Connection): String =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'producer_output_evidence'",
      ).use { rows ->
        check(rows.next()) { "producer_output_evidence is absent." }
        rows.getString("sql")
      }
    }

  private fun producerEvidenceRows(connection: java.sql.Connection): List<ProducerEvidenceRow> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT generation, attempt, recorded_at, sha256, payload FROM producer_output_evidence ORDER BY attempt",
      ).use { rows ->
        buildList {
          while (rows.next()) {
            add(
              ProducerEvidenceRow(
                generation = rows.getInt("generation"),
                attempt = rows.getInt("attempt"),
                recordedAt = rows.getString("recorded_at"),
                sha256 = rows.getString("sha256"),
                payload = rows.getBytes("payload"),
              ),
            )
          }
        }
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

  private fun tableNames(connection: Connection): Set<String> = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

  private fun allTableRowCounts(connection: Connection): Map<String, Int> = tableNames(connection)
    .filterNot { it.startsWith("sqlite_") }
    .associateWith { table -> rowCount(connection, table) }

  private fun findingRows(connection: Connection): List<List<Any?>> = connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      SELECT review_run_id, finding_id, severity, confidence, location, description, finding_text
      FROM findings
      ORDER BY review_run_id, finding_id
      """.trimIndent(),
    ).use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add((1..resultSet.metaData.columnCount).map(resultSet::getObject))
        }
      }
    }
  }

  private fun rowCount(connection: Connection, tableName: String): Int = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { resultSet ->
      check(resultSet.next())
      resultSet.getInt(1)
    }
  }

  private fun scalarInt(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { resultSet ->
      check(resultSet.next())
      resultSet.getInt(1)
    }
  }

  private fun scalarString(connection: Connection, sql: String): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { resultSet ->
        if (resultSet.next()) resultSet.getString(1) else null
      }
    }

  private fun tableIndexNames(connection: Connection): Set<String> = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { resultSet ->
      buildSet {
        while (resultSet.next()) {
          add(resultSet.getString("name"))
        }
      }
    }
  }

  private fun tableInfo(connection: Connection, tableName: String): List<TableColumnInfo> =
    connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(
              TableColumnInfo(
                name = resultSet.getString("name"),
                notNull = resultSet.getInt("notnull") == 1,
              ),
            )
          }
        }
      }
    }

  private fun telemetryOutboxRows(connection: Connection): List<List<Any?>> = connection.createStatement().use {
    it.executeQuery("SELECT id, event_name, synced_at, last_error FROM telemetry_outbox ORDER BY id").use { rows ->
      buildList {
        while (rows.next()) {
          add((1..rows.metaData.columnCount).map(rows::getObject))
        }
      }
    }
  }

  /** A pre-migration outbox: two healthy rows carrying '' and one real delivery failure. */
  private fun createLegacyTelemetryOutboxDatabase(dbPath: Path) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE telemetry_outbox (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_name TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            synced_at TEXT,
            last_error TEXT NOT NULL DEFAULT ''
          )
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO telemetry_outbox (id, event_name, payload_json, synced_at, last_error) VALUES
            (1, 'review_finished', '{}', '2026-01-01T00:00:00Z', ''),
            (2, 'review_finished', '{}', NULL, 'boom'),
            (3, 'goal_finished', '{}', NULL, '')
          """.trimIndent(),
        )
      }
    }
  }

  private data class TableColumnInfo(val name: String, val notNull: Boolean)

  private data class MigrationRow(
    val version: Int,
    val name: String,
    val appliedAt: String,
  )

  private data class ProducerEvidenceRow(
    val generation: Int,
    val attempt: Int,
    val recordedAt: String,
    val sha256: String,
    val payload: ByteArray?,
  )

  private companion object {
    const val SCHEMA_MIGRATIONS_TABLE: String = "schema_migrations"
    const val REAL_STORE_ENV: String = "SKILL_BILL_REAL_STORE_DB"
    const val MIGRATION_FIXTURE_ENV: String = "SKILL_BILL_MIGRATION_FIXTURE_DB"

    // The routed_skill and detected_stack prose variants actually observed in the real store.
    val LEGACY_ROUTED_SKILL_VARIANTS: List<String> = listOf(
      "bill-kmp-code-review",
      "bill-kmp-code-review (parallel)",
      "bill-kmp-code-review-persistence",
      "skillbill:bill-kmp-code-review",
      "`bill-kmp-code-review`",
      "Routed to bill-kmp-code-review for the KMP pack",
    )
    val LEGACY_STACK_VARIANTS: List<String> =
      listOf("kotlin", "Kotlin", "Kotlin/JVM", "kotlin (jvm backend)", "  KOTLIN  ")
    const val AMBIGUOUS_ROUTED_SKILL: String = "bill-kmp-code-review, bill-ios-code-review"

    const val VERSION_KEYED_SCHEMA_MIGRATIONS_SQL: String =
      """
      CREATE TABLE schema_migrations (
        version INTEGER PRIMARY KEY,
        name TEXT NOT NULL UNIQUE,
        applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """

    const val PRE_GENERATION_PRODUCER_OUTPUT_EVIDENCE_SQL: String =
      """
      CREATE TABLE producer_output_evidence (
        workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
        PRIMARY KEY (workflow_id, phase_id, attempt)
      )
      """

    const val PRE_AGENT_PRODUCER_OUTPUT_EVIDENCE_SQL: String =
      """
      CREATE TABLE producer_output_evidence (
        workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
        PRIMARY KEY (workflow_id, phase_id, generation, attempt)
      )
      """

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

private fun versionIsPrimaryKey(connection: java.sql.Connection): Boolean =
  connection.prepareStatement("SELECT pk FROM pragma_table_info('schema_migrations') WHERE name = 'version'")
    .use { statement ->
      statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getInt("pk") > 0 }
    }

private fun tableExists(connection: java.sql.Connection, table: String): Boolean =
  connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { resultSet -> resultSet.next() }
  }
