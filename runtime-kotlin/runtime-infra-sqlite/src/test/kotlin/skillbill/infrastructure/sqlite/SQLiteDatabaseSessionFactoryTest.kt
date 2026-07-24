package skillbill.infrastructure.sqlite

import skillbill.model.EnvironmentContext
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.WorkflowStateRecord
import java.nio.file.Files
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
        unitOfWork.workflowStates.saveFeatureImplementWorkflow(
          WorkflowStateRecord(
            workflowId = "wfl-rollback",
            sessionId = "fis-rollback",
            workflowName = "bill-feature-task",
            contractVersion = "",
            workflowStatus = "running",
            currentStepId = "implement",
            stepsJson = "[]",
            artifactsJson = "{}",
            startedAt = null,
            updatedAt = null,
            finishedAt = null,
          ),
        )
        error("force rollback")
      }
    }

    val saved =
      database.read(dbPath.toString()) { unitOfWork ->
        unitOfWork.workflowStates.getFeatureImplementWorkflow("wfl-rollback")
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
  fun `write transactions reserve the writer before entering the transaction block`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-write-reservation")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val workflowId = "wfl-write-reservation"
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureImplementWorkflow(workflowRecord(workflowId))
    }
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val first = executor.submit {
        database.transaction(dbPath.toString()) { unitOfWork ->
          val workflow = requireNotNull(unitOfWork.workflowStates.getFeatureImplementWorkflow(workflowId))
          firstEntered.countDown()
          check(releaseFirst.await(5, TimeUnit.SECONDS))
          unitOfWork.workflowStates.saveFeatureImplementWorkflow(workflow.copy(artifactsJson = "{\"writer\":1}"))
        }
      }
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
      val second = executor.submit {
        secondStarted.countDown()
        database.transaction(dbPath.toString()) { unitOfWork ->
          secondEntered.countDown()
          val workflow = requireNotNull(unitOfWork.workflowStates.getFeatureImplementWorkflow(workflowId))
          unitOfWork.workflowStates.saveFeatureImplementWorkflow(workflow.copy(artifactsJson = "{\"writer\":2}"))
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
    val workflowId = "wfl-crash-reconcile"
    // Seed the row and the expired worker lease exactly as production does: acquisition runs under
    // read (the store method owns its own BEGIN IMMEDIATE), never inside an outer transaction.
    database.transaction(dbPath.toString()) { it.workflowStates.saveFeatureTaskRuntimeWorkflow(runtimeRow(workflowId)) }
    val updatedAt = database.read(dbPath.toString()) {
      it.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)?.updatedAt
    }
    val ownership = expiredOwnership(workflowId)
    database.read(dbPath.toString()) { it.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, updatedAt) }

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
    workflowName = "bill-feature-task-runtime",
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

  private fun workflowRecord(workflowId: String) = WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = "fis-write-reservation",
    workflowName = "bill-feature-task",
    contractVersion = "1.0",
    workflowStatus = "running",
    currentStepId = "implement",
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = null,
    updatedAt = null,
    finishedAt = null,
  )
}
