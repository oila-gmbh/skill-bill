package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.error.DatabaseAccessError
import skillbill.model.EnvironmentContext
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteDatabaseSessionFactoryTest {
  @Test
  fun `transaction rolls back repository writes when the use case fails`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-session")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))

    assertFailsWith<IllegalStateException> {
      database.transaction(dbPath.toString()) { unitOfWork ->
        unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(
          WorkflowStateRecord(
            workflowId = "wftr-rollback",
            sessionId = "ftr-rollback",
            workflowName = "bill-feature-task",
            contractVersion = "",
            workflowStatus = "running",
            currentStepId = "implement",
            stepsJson = "[]",
            artifactsJson = "{}",
            startedAt = null,
            updatedAt = null,
            finishedAt = null,
            mode = FeatureTaskWorkflowMode.RUNTIME,
          ),
        )
        error("force rollback")
      }
    }

    val saved =
      database.read(dbPath.toString()) { unitOfWork ->
        unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow("wftr-rollback")
      }
    assertNull(saved)
  }

  @Test
  fun `resolve path and existence are provided by the database session factory`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-session-path")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))

    assertEquals(dbPath.toAbsolutePath().normalize(), database.resolveDbPath(dbPath.toString()))
    assertEquals(false, database.databaseExists(dbPath.toString()))
    database.read(dbPath.toString()) { Unit }
    assertEquals(true, database.databaseExists(dbPath.toString()))
  }

  @Test
  fun `read if present does not create an absent database`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-if-present")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))

    assertNull(database.readIfPresent(dbPath.toString()) { Unit })
    assertFalse(Files.exists(dbPath))
  }

  @Test
  fun `read if present rejects an existing schemaless database`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-if-present-schemaless")
    val dbPath = Files.createFile(tempDir.resolve("metrics.db"))
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))

    assertFailsWith<DatabaseAccessError> {
      database.readIfPresent(dbPath.toString()) { Unit }
    }
  }

  @Test
  fun `read opens an existing database while another connection holds the writer lock`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-contention")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val workflowId = "wftr-read-contention"
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord(workflowId))
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        val status = database.read(dbPath.toString()) { unitOfWork ->
          unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus
        }
        assertEquals("running", status)
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }
  }

  @Test
  fun `the read seam hands out a connection without write capability`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-only-open")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord("wftr-read-only"))
    }

    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap(), userHome = tempDir)
      .use { openDb ->
        assertEquals(
          "running",
          workflowStatus(openDb.connection, "wftr-read-only"),
          "A read-only open must still serve queries against a WAL database closed by its writer.",
        )
        assertFailsWith<SQLException>("The read seam must not hand out a write-capable connection.") {
          openDb.connection.createStatement().use {
            it.executeUpdate("UPDATE feature_task_workflows SET workflow_status = 'tampered'")
          }
        }
        assertFailsWith<SQLException>("The read seam must not permit schema mutation.") {
          openDb.connection.createStatement().use { it.execute("CREATE TABLE read_path_probe (x TEXT)") }
        }
      }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertEquals("running", workflowStatus(connection, "wftr-read-only"))
      assertFalse(tableExists(connection, "read_path_probe"), "The read seam must leave no schema behind.")
    }
  }

  @Test
  fun `the read seam serves a query while another connection holds the writer lock`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-only-contention")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord("wftr-read-only-contention"))
    }

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
      try {
        val startedAt = System.nanoTime()
        val status = database.read(dbPath.toString()) { unitOfWork ->
          unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow("wftr-read-only-contention")?.workflowStatus
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals("running", status)
        // Well inside the 5000 ms busy_timeout, so a pass proves the read never contended for the
        // write lock rather than having waited one out.
        assertTrue(elapsedMillis < 1_000, "A contended read must return promptly, took ${elapsedMillis}ms.")
      } finally {
        writer.createStatement().use { it.execute("ROLLBACK") }
      }
    }
  }

  @Test
  fun `two statements in one read block observe one snapshot across a writer commit`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-snapshot")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val workflowId = "wftr-read-snapshot"
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord(workflowId))
    }

    val observed = database.read(dbPath.toString()) { unitOfWork ->
      val before = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
        writer.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
        writer.prepareStatement("UPDATE feature_task_workflows SET workflow_status = ? WHERE workflow_id = ?")
          .use { statement ->
            statement.setString(1, "complete")
            statement.setString(2, workflowId)
            assertEquals(1, statement.executeUpdate())
          }
      }
      before to unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus
    }

    assertEquals("running" to "running", observed, "A read block must not observe a writer commit landing inside it.")
    assertEquals(
      "complete",
      database.read(dbPath.toString()) {
        it.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus
      },
      "The next read block must start from a fresh snapshot carrying the committed write.",
    )
  }

  @Test
  fun `a writer commits while a read block holds its snapshot open`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-snapshot-writer")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val workflowId = "wftr-read-snapshot-writer"
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord(workflowId))
    }
    val executor = Executors.newSingleThreadExecutor()

    try {
      database.read(dbPath.toString()) { unitOfWork ->
        // Materialize the snapshot first: BEGIN DEFERRED takes its read mark at the first statement.
        assertEquals("running", unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus)
        val writer = executor.submit {
          database.transaction(dbPath.toString()) { writerWork ->
            val row = requireNotNull(writerWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId))
            writerWork.workflowStates.saveFeatureTaskRuntimeWorkflow(row.copy(artifactsJson = "{\"writer\":1}"))
          }
        }
        // A deferred read takes no write lock, so this commit must land without waiting for the block.
        writer.get(10, TimeUnit.SECONDS)
      }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `read path databases stay in wal mode so a held snapshot never blocks a writer`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-journal-mode")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord("wftr-journal-mode"))
    }

    // openReadDb configures its own connection with enableWal = false and a read-only connection cannot
    // change the mode, so WAL is asserted against the file rather than assumed from the writer's PRAGMA.
    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap(), userHome = tempDir)
      .use { openDb -> assertEquals("wal", journalMode(openDb.connection)) }
  }

  @Test
  fun `a rollback-journal database still yields a consistent read snapshot`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-read-rollback-journal")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val workflowId = "wftr-rollback-journal"
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord(workflowId))
    }
    // Every factory-opened writer re-asserts WAL, so the non-WAL case is reached with a raw connection.
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { it.execute("PRAGMA journal_mode = DELETE") }
      assertEquals("delete", journalMode(connection))
    }
    val executor = Executors.newSingleThreadExecutor()

    try {
      val observed = database.read(dbPath.toString()) { unitOfWork ->
        val before = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus
        val writer = executor.submit { rawWriterCommit(dbPath.toString(), workflowId, "complete") }
        val after = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus
        // Under a rollback journal the writer waits out the reader's shared lock — the tradeoff WAL
        // removes, which is why the journal mode of read-path databases is asserted rather than assumed.
        Triple(before, after, writer)
      }
      assertEquals(observed.first, observed.second, "A rollback-journal read block must still see one snapshot.")
      assertEquals("running", observed.first)
      observed.third.get(10, TimeUnit.SECONDS)
      assertEquals(
        "complete",
        database.read(dbPath.toString()) {
          it.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus
        },
      )
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `write transactions reserve the writer before entering the transaction block`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-write-reservation")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val workflowId = "wftr-write-reservation"
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflowRecord(workflowId))
    }
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val first = executor.submit {
        database.transaction(dbPath.toString()) { unitOfWork ->
          val workflow = requireNotNull(unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId))
          firstEntered.countDown()
          check(releaseFirst.await(5, TimeUnit.SECONDS))
          unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflow.copy(artifactsJson = "{\"writer\":1}"))
        }
      }
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
      val second = executor.submit {
        secondStarted.countDown()
        database.transaction(dbPath.toString()) { unitOfWork ->
          secondEntered.countDown()
          val workflow = requireNotNull(unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId))
          unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(workflow.copy(artifactsJson = "{\"writer\":2}"))
        }
      }

      assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
      assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS))
      releaseFirst.countDown()
      first.get(5, TimeUnit.SECONDS)
      second.get(5, TimeUnit.SECONDS)
      assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
    } finally {
      releaseFirst.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `crash reconcile write composes inside a real database transaction without nesting`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-crash-reconcile")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val workflowId = "wftr-crash-reconcile"
    // Seed the row and the expired worker lease exactly as production does: acquisition runs under
    // read (the store method owns its own BEGIN IMMEDIATE), never inside an outer transaction.
    database.transaction(dbPath.toString()) { it.workflowStates.saveFeatureTaskRuntimeWorkflow(runtimeRow(workflowId)) }
    val updatedAt = database.read(dbPath.toString()) {
      it.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.updatedAt
    }
    val ownership = expiredOwnership(workflowId)
    database.selfManagedWrite(dbPath.toString()) {
      it.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, updatedAt)
    }

    // The production reconciler and goal-parent both call the reconcile write inside
    // database.transaction; assert that composition succeeds instead of raising a nested BEGIN.
    val reconciled = database.transaction(dbPath.toString()) {
      it.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
        workflowId = workflowId,
        ownerToken = ownership.ownerToken,
        generation = ownership.generation,
        interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
        nowInstant = "2999-01-01T00:00:00Z",
      )
    }

    assertTrue(reconciled)
    database.read(dbPath.toString()) {
      assertEquals("pending", it.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.workflowStatus)
      assertNull(it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId))
    }
  }

  private fun runtimeRow(workflowId: String) = WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = "ftr-crash-reconcile",
    workflowName = "bill-feature-task",
    contractVersion = "1.0",
    workflowStatus = "running",
    currentStepId = "implement",
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = null,
    updatedAt = null,
    finishedAt = null,
    mode = FeatureTaskWorkflowMode.RUNTIME,
  )

  private fun expiredOwnership(workflowId: String) = FeatureTaskRuntimeWorkerOwnership(
    workflowId = workflowId,
    generation = 1,
    ownerToken = "owner-token-crash0001",
    hostIdentity = "host",
    bootIdentity = "boot",
    pid = 4242,
    processBirthToken = "birth-4242",
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = "2000-01-01T00:00:00Z",
    expiresAt = "2000-01-01T00:00:30Z",
    phaseId = "implement",
    phaseAttempt = 1,
  )

  private fun journalMode(connection: Connection): String? = connection.createStatement().use { statement ->
    statement.executeQuery("PRAGMA journal_mode").use { rows ->
      if (rows.next()) rows.getString(1)?.lowercase() else null
    }
  }

  private fun rawWriterCommit(dbPath: String, workflowId: String, status: String) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
      writer.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
      writer.prepareStatement("UPDATE feature_task_workflows SET workflow_status = ? WHERE workflow_id = ?")
        .use { statement ->
          statement.setString(1, status)
          statement.setString(2, workflowId)
          statement.executeUpdate()
        }
    }
  }

  private fun workflowStatus(connection: Connection, workflowId: String): String? = connection
    .prepareStatement("SELECT workflow_status FROM feature_task_workflows WHERE workflow_id = ?")
    .use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

  private fun tableExists(connection: Connection, table: String): Boolean = connection
    .prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")
    .use { statement ->
      statement.setString(1, table)
      statement.executeQuery().use { rows -> rows.next() }
    }

  private fun workflowRecord(workflowId: String) = WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = "ftr-write-reservation",
    workflowName = "bill-feature-task",
    contractVersion = "1.0",
    workflowStatus = "running",
    currentStepId = "implement",
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = null,
    updatedAt = null,
    finishedAt = null,
    mode = FeatureTaskWorkflowMode.RUNTIME,
  )
}
