package skillbill.db

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.DbConstants
import skillbill.db.workflow.WorkflowStateRow
import skillbill.db.workflow.WorkflowStateStore
import skillbill.error.InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowStateStoreTest {
  @Test
  fun `feature task execution identity can be read exactly at adoption boundary`() {
    val dbPath = Files.createTempDirectory("goal-child-identity-read").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val workflow = goalChildWorkflow("wfl-child", "wfl-parent")
      val identity = goalChildIdentity(workflow)

      store.saveFeatureTaskRuntimeWorkflow(workflow)
      store.saveFeatureTaskExecutionIdentity(identity)

      assertEquals(identity, store.getFeatureTaskExecutionIdentity(identity.workflowId))
      assertEquals(null, store.getFeatureTaskExecutionIdentity("wfl-missing"))
    }
  }

  @Test
  fun `hard reset deletion removes only goal children owned by the parent workflow`() {
    val dbPath = Files.createTempDirectory("goal-child-reset").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val target = goalChildWorkflow("wfl-target", "wfl-parent")
      val siblingGoal = goalChildWorkflow("wfl-other-goal", "wfl-other-parent")
      val standalone = goalChildWorkflow("wfl-standalone", "wfl-parent")
      listOf(target, siblingGoal, standalone).forEach(store::saveFeatureTaskRuntimeWorkflow)
      listOf(target, siblingGoal).forEach { row -> store.saveFeatureTaskExecutionIdentity(goalChildIdentity(row)) }
      store.saveFeatureTaskExecutionIdentity(
        goalChildIdentity(standalone).copy(routeScope = FeatureTaskRouteScope.STANDALONE),
      )

      assertEquals(1, store.deleteGoalChildWorkflowsByParent("wfl-parent"))

      assertEquals(null, store.getFeatureTaskRuntimeWorkflow(target.workflowId))
      assertNotNull(store.getFeatureTaskRuntimeWorkflow(siblingGoal.workflowId))
      assertNotNull(store.getFeatureTaskRuntimeWorkflow(standalone.workflowId))
    }
  }

  @Test
  fun `runtime worker ownership is acquired fenced transferred heartbeated and released`() {
    val dbPath = Files.createTempDirectory("runtime-worker-lease").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = workflowRow(
        workflowId = "wfl-worker",
        sessionId = "ftr-worker",
        workflowName = "bill-feature-task-runtime",
        currentStepId = "implement",
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ).copy(workflowStatus = "running")
      store.saveFeatureTaskRuntimeWorkflow(row)
      val updatedAt = assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).updatedAt
      val initial = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-0001")

      assertTrue(store.acquireFeatureTaskRuntimeWorker(initial, updatedAt))
      assertEquals(initial, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
      assertTrue(store.reserveFeatureTaskRuntimeWorkerTakeover(row.workflowId, initial.ownerToken, 1))
      val replacement = workerOwnership(row.workflowId, generation = 2, ownerToken = "owner-token-0002")
      assertTrue(store.transferFeatureTaskRuntimeWorker(replacement, initial.ownerToken, 1))
      assertEquals(replacement, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
      assertTrue(store.heartbeatFeatureTaskRuntimeWorker(replacement.copy(heartbeatAt = "2026-07-14T10:01:00Z")))
      assertTrue(store.releaseFeatureTaskRuntimeWorker(row.workflowId, replacement.ownerToken, 2))
      assertEquals(null, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
    }
  }

  @Test
  fun `runtime worker takeover reservation is single caller CAS`() {
    val dbPath = Files.createTempDirectory("runtime-worker-contention").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = workflowRow(
        workflowId = "wfl-contention",
        sessionId = "ftr-contention",
        workflowName = "bill-feature-task-runtime",
        currentStepId = "implement",
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ).copy(workflowStatus = "paused")
      store.saveFeatureTaskRuntimeWorkflow(row)
      val updatedAt = assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 4, ownerToken = "owner-token-0004")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))

      assertTrue(store.reserveFeatureTaskRuntimeWorkerTakeover(row.workflowId, ownership.ownerToken, 4))
      assertEquals(false, store.reserveFeatureTaskRuntimeWorkerTakeover(row.workflowId, ownership.ownerToken, 4))
    }
  }

  @Test
  fun `runtime worker ownership rejects malformed and inverted lease timestamps at read seam`() {
    val dbPath = Files.createTempDirectory("runtime-worker-invalid-lease").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = workflowRow(
        workflowId = "wfl-invalid-lease",
        sessionId = "ftr-invalid-lease",
        workflowName = "bill-feature-task-runtime",
        currentStepId = "implement",
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ).copy(workflowStatus = "paused")
      store.saveFeatureTaskRuntimeWorkflow(row)
      val updatedAt = assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).updatedAt
      assertTrue(
        store.acquireFeatureTaskRuntimeWorker(
          workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-0001"),
          updatedAt,
        ),
      )

      connection.prepareStatement(
        "UPDATE feature_task_runtime_worker_leases SET heartbeat_at = ? WHERE workflow_id = ?",
      ).use {
        it.setString(1, "not-an-instant")
        it.setString(2, row.workflowId)
        it.executeUpdate()
      }
      assertFailsWith<InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError> {
        store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId)
      }

      connection.prepareStatement(
        "UPDATE feature_task_runtime_worker_leases SET heartbeat_at = ?, expires_at = ? WHERE workflow_id = ?",
      ).use {
        it.setString(1, "2026-07-14T10:05:00Z")
        it.setString(2, "2026-07-14T10:05:00Z")
        it.setString(3, row.workflowId)
        it.executeUpdate()
      }
      assertFailsWith<InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError> {
        store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId)
      }
    }
  }

  @Test
  fun `feature task runtime table contract version default matches schema contract version const`() {
    // Pin the table default to the validator's schema version so a future
    // schema bump that forgets it breaks the build, not production writes.
    assertEquals(
      WORKFLOW_STATE_CONTRACT_VERSION,
      DbConstants.FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION,
      "FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION must equal WORKFLOW_STATE_CONTRACT_VERSION " +
        "($WORKFLOW_STATE_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `feature implement workflow rows round trip with updated json payloads`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflows").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val initialRow =
        WorkflowStateRow(
          workflowId = "wfl-001",
          sessionId = "fis-001",
          workflowName = "bill-feature-task",
          contractVersion = "",
          workflowStatus = "running",
          currentStepId = "plan",
          stepsJson = """[{"step_id":"plan","status":"running"}]""",
          artifactsJson = """{"assessment":{"feature_name":"persistence-core"}}""",
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
        )

      store.saveFeatureImplementWorkflow(initialRow)
      store.saveFeatureImplementWorkflow(
        initialRow.copy(
          workflowStatus = "completed",
          currentStepId = "finish",
          artifactsJson = """{"implementation_summary":{"files_created":7}}""",
          finishedAt = "2026-04-23 00:00:00",
        ),
      )

      val saved = assertNotNull(store.getFeatureImplementWorkflow("wfl-001"))
      assertEquals(DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION, saved.contractVersion)
      assertEquals("bill-feature-task", saved.workflowName)
      assertEquals(FeatureTaskWorkflowMode.PROSE, saved.mode)
      assertEquals("bill-feature-task-prose", saved.implementationSkill)
      assertEquals("completed", saved.workflowStatus)
      assertEquals("finish", saved.currentStepId)
      assertEquals("""{"implementation_summary":{"files_created":7}}""", saved.artifactsJson)
      assertEquals("2026-04-23 00:00:00", saved.finishedAt)
    }
  }

  @Test
  fun `feature verify workflow rows preserve explicit contract version and json fields`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflows").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)

      store.saveFeatureVerifyWorkflow(
        WorkflowStateRow(
          workflowId = "wfv-001",
          sessionId = "fvr-001",
          workflowName = "bill-feature-verify",
          contractVersion = "0.1",
          workflowStatus = "running",
          currentStepId = "code_review",
          stepsJson = """[{"step_id":"code_review","status":"running"}]""",
          artifactsJson = """{"review_result":{"verdict":"approve"}}""",
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
        ),
      )

      val saved = assertNotNull(store.getFeatureVerifyWorkflow("wfv-001"))
      assertEquals("bill-feature-verify", saved.workflowName)
      assertEquals("0.1", saved.contractVersion)
      assertEquals("code_review", saved.currentStepId)
      assertEquals("""{"review_result":{"verdict":"approve"}}""", saved.artifactsJson)
    }
  }

  @Test
  fun `terminal workflow update records finished timestamp when caller leaves it empty`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-terminal").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val initialRow =
        workflowRow(
          workflowId = "wfl-terminal",
          sessionId = "fis-terminal",
          workflowName = "bill-feature-task",
          currentStepId = "assess",
        )

      store.saveFeatureImplementWorkflow(initialRow)
      store.saveFeatureImplementWorkflow(
        initialRow.copy(
          workflowStatus = "abandoned",
          currentStepId = "finish",
          finishedAt = "",
        ),
      )

      val saved = assertNotNull(store.getFeatureImplementWorkflow("wfl-terminal"))
      assertEquals("abandoned", saved.workflowStatus)
      assertNotNull(saved.finishedAt)
    }
  }

  @Test
  fun `workflow state entry starts at supplied start time and changes only on a status transition`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-state-entry").resolve("metrics.db")
    val startedAt = "2999-05-01T12:00:00.123456789Z"

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val initial = workflowRow(
        workflowId = "wfl-state-entry",
        sessionId = "fis-state-entry",
        workflowName = "bill-feature-task",
        currentStepId = "assess",
      ).copy(startedAt = startedAt)

      store.saveFeatureImplementWorkflow(initial)
      val inserted = assertNotNull(store.getFeatureImplementWorkflow("wfl-state-entry"))
      assertEquals(startedAt, inserted.startedAt)
      assertEquals(startedAt, inserted.stateEnteredAt)
      assertEquals(false, inserted.stateEnteredAtEstimated)

      store.saveFeatureImplementWorkflow(initial.copy(currentStepId = "plan", artifactsJson = "{\"plan\":{}}"))
      val sameStatus = assertNotNull(store.getFeatureImplementWorkflow("wfl-state-entry"))
      assertEquals(startedAt, sameStatus.stateEnteredAt)
      assertEquals(false, sameStatus.stateEnteredAtEstimated)

      store.saveFeatureImplementWorkflow(sameStatus.copy(workflowStatus = "blocked", currentStepId = "plan"))
      val transitioned = assertNotNull(store.getFeatureImplementWorkflow("wfl-state-entry"))
      assertEquals("blocked", transitioned.workflowStatus)
      assertTrue(Instant.parse(transitioned.stateEnteredAt).isAfter(Instant.parse(startedAt)))
      assertEquals(false, transitioned.stateEnteredAtEstimated)

      assertRuntimeAndVerifyStateTransitions(store, initial, startedAt)
    }
  }

  @Test
  fun `workflow inserts without a supplied start time use one effective timestamp`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-insert-state-entry").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      store.saveFeatureImplementWorkflow(
        workflowRow("wfl-insert", "fis-insert", "bill-feature-task", "assess"),
      )
      store.saveFeatureTaskRuntimeWorkflow(
        workflowRow(
          workflowId = "wftr-insert",
          sessionId = "ftr-insert",
          workflowName = "bill-feature-task",
          currentStepId = "preplan",
          mode = FeatureTaskWorkflowMode.RUNTIME,
        ),
      )
      store.saveFeatureVerifyWorkflow(
        workflowRow("wfv-insert", "fvr-insert", "bill-feature-verify", "collect_inputs"),
      )

      listOf(
        assertNotNull(store.getFeatureImplementWorkflow("wfl-insert")),
        assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-insert")),
        assertNotNull(store.getFeatureVerifyWorkflow("wfv-insert")),
      ).forEach { inserted ->
        assertEquals(inserted.startedAt, inserted.stateEnteredAt)
        assertEquals(false, inserted.stateEnteredAtEstimated)
        assertTrue(!inserted.startedAt.isNullOrBlank())
      }
    }
  }

  @Test
  fun `concurrent workflow status transitions serialize strictly increasing state entry times`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-concurrent-state").resolve("metrics.db")
    val initial = workflowRow(
      workflowId = "wfl-concurrent-state-entry",
      sessionId = "fis-concurrent-state-entry",
      workflowName = "bill-feature-task",
      currentStepId = "assess",
    ).copy(startedAt = "2999-05-01T12:00:00.123456789Z")

    prepareConcurrentWorkflowTransitions(dbPath, initial)

    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val transitions = listOf("blocked", "failed").map { status ->
        executor.submit {
          ready.countDown()
          check(start.await(5, TimeUnit.SECONDS)) { "Concurrent transition start timed out." }
          DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            WorkflowStateStore(connection).saveFeatureImplementWorkflow(
              initial.copy(workflowStatus = status, currentStepId = "plan"),
            )
          }
        }
      }

      assertTrue(ready.await(5, TimeUnit.SECONDS))
      start.countDown()
      transitions.forEach { it.get(5, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val entries = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT state_entered_at FROM workflow_transition_log ORDER BY rowid").use { resultSet ->
          buildList {
            while (resultSet.next()) {
              add(resultSet.getString("state_entered_at"))
            }
          }
        }
      }

      assertEquals(2, entries.size)
      assertTrue(Instant.parse(entries[1]).isAfter(Instant.parse(entries[0])))
    }
  }

  private fun assertRuntimeAndVerifyStateTransitions(
    store: WorkflowStateStore,
    initial: WorkflowStateRow,
    startedAt: String,
  ) {
    val runtimeInitial = initial.copy(
      workflowId = "wftr-state-entry",
      sessionId = "ftr-state-entry",
      mode = FeatureTaskWorkflowMode.RUNTIME,
    )
    store.saveFeatureTaskRuntimeWorkflow(runtimeInitial)
    val runtimeInserted = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-state-entry"))
    assertEquals(startedAt, runtimeInserted.stateEnteredAt)

    store.saveFeatureTaskRuntimeWorkflow(runtimeInserted.copy(workflowStatus = "blocked", currentStepId = "plan"))
    val runtimeTransitioned = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-state-entry"))
    assertTrue(Instant.parse(runtimeTransitioned.stateEnteredAt).isAfter(Instant.parse(startedAt)))
    assertEquals(false, runtimeTransitioned.stateEnteredAtEstimated)

    val verifyInitial = WorkflowStateRow(
      workflowId = "wfv-state-entry",
      sessionId = "fvr-state-entry",
      workflowName = "bill-feature-verify",
      contractVersion = "0.1",
      workflowStatus = "running",
      currentStepId = "gather_diff",
      stepsJson = "[]",
      artifactsJson = "{}",
      startedAt = startedAt,
      updatedAt = null,
      finishedAt = null,
    )
    store.saveFeatureVerifyWorkflow(verifyInitial)
    val verifyInserted = assertNotNull(store.getFeatureVerifyWorkflow("wfv-state-entry"))
    assertEquals(startedAt, verifyInserted.stateEnteredAt)

    store.saveFeatureVerifyWorkflow(verifyInitial.copy(currentStepId = "code_review"))
    val verifySameStatus = assertNotNull(store.getFeatureVerifyWorkflow("wfv-state-entry"))
    assertEquals(startedAt, verifySameStatus.stateEnteredAt)

    store.saveFeatureVerifyWorkflow(verifySameStatus.copy(workflowStatus = "completed", currentStepId = "finish"))
    val verifyTransitioned = assertNotNull(store.getFeatureVerifyWorkflow("wfv-state-entry"))
    assertTrue(Instant.parse(verifyTransitioned.stateEnteredAt).isAfter(Instant.parse(startedAt)))
    assertEquals(false, verifyTransitioned.stateEnteredAtEstimated)
  }

  private fun prepareConcurrentWorkflowTransitions(dbPath: java.nio.file.Path, initial: WorkflowStateRow) {
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      WorkflowStateStore(connection).saveFeatureImplementWorkflow(initial)
      connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE workflow_transition_log (state_entered_at TEXT NOT NULL)")
        statement.execute(
          """
          CREATE TRIGGER workflow_state_transition_log
          AFTER UPDATE OF workflow_status ON feature_task_workflows
          WHEN OLD.workflow_status != NEW.workflow_status
          BEGIN
            INSERT INTO workflow_transition_log (state_entered_at) VALUES (NEW.state_entered_at);
          END
          """.trimIndent(),
        )
      }
    }
  }

  @Test
  fun `workflow lists and latest use updated timestamp then rowid ordering for both families`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)

      listOf("wfl-001", "wfl-002", "wfl-003").forEachIndexed { index, workflowId ->
        store.saveFeatureImplementWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "fis-00$index",
            workflowName = "bill-feature-task",
            currentStepId = "assess",
          ),
        )
      }
      listOf("wfv-001", "wfv-002").forEachIndexed { index, workflowId ->
        store.saveFeatureVerifyWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "fvr-00$index",
            workflowName = "bill-feature-verify",
            currentStepId = "gather_diff",
          ),
        )
      }

      assertEquals(listOf("wfl-003", "wfl-002"), store.listFeatureImplementWorkflows(2).map { it.workflowId })
      assertEquals("wfl-003", store.latestFeatureImplementWorkflow()?.workflowId)
      assertEquals(listOf("wfv-002", "wfv-001"), store.listFeatureVerifyWorkflows(10).map { it.workflowId })
      assertEquals("wfv-002", store.latestFeatureVerifyWorkflow()?.workflowId)
    }
  }

  @Test
  fun `feature task runtime workflow rows round trip with per-phase records and appended ledger`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-task-runtime").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val artifactsJson = taskRuntimeArtifactsJson

      val initialRow =
        WorkflowStateRow(
          workflowId = "wftr-001",
          sessionId = "ftr-001",
          workflowName = "bill-feature-task",
          mode = FeatureTaskWorkflowMode.RUNTIME,
          contractVersion = "",
          workflowStatus = "running",
          currentStepId = "plan",
          stepsJson = """[{"step_id":"plan","status":"completed"}]""",
          artifactsJson = artifactsJson,
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
        )

      store.saveFeatureTaskRuntimeWorkflow(initialRow)

      val saved = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-001"))
      assertEquals(DbConstants.FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION, saved.contractVersion)
      assertEquals("bill-feature-task", saved.workflowName)
      assertEquals(FeatureTaskWorkflowMode.RUNTIME, saved.mode)
      assertEquals("bill-feature-task-runtime", saved.implementationSkill)
      assertEquals("plan", saved.currentStepId)
      assertEquals(artifactsJson, saved.artifactsJson)

      assertFailsWith<InvalidWorkflowStateSchemaError> {
        store.getFeatureImplementWorkflow("wftr-001")
      }
      assertEquals(null, store.getFeatureVerifyWorkflow("wftr-001"))
      assertEquals(FeatureTaskWorkflowMode.RUNTIME, store.getFeatureTaskWorkflow("wftr-001")?.mode)
    }
  }

  @Test
  fun `feature task runtime upsert preserves the original started_at across a second save`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-task-runtime-started").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val initialRow =
        workflowRow(
          workflowId = "wftr-started",
          sessionId = "ftr-started",
          workflowName = "bill-feature-task",
          currentStepId = "plan",
          mode = FeatureTaskWorkflowMode.RUNTIME,
        )

      store.saveFeatureTaskRuntimeWorkflow(initialRow)
      val firstStartedAt = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-started")).startedAt
      assertNotNull(firstStartedAt)

      // A second save of the same workflow_id (e.g. advancing the phase) must not reset
      // started_at: the upsert leaves it immutable and only refreshes updated_at.
      store.saveFeatureTaskRuntimeWorkflow(
        initialRow.copy(currentStepId = "implement", startedAt = "2099-01-01 00:00:00"),
      )

      val resaved = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-started"))
      assertEquals(firstStartedAt, resaved.startedAt)
      assertEquals("implement", resaved.currentStepId)
    }
  }

  @Test
  fun `feature task runtime workflow lists and latest use updated timestamp then rowid ordering`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-task-runtime-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)

      listOf("wftr-001", "wftr-002", "wftr-003").forEachIndexed { index, workflowId ->
        store.saveFeatureTaskRuntimeWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "ftr-00$index",
            workflowName = "bill-feature-task",
            currentStepId = "plan",
            mode = FeatureTaskWorkflowMode.RUNTIME,
          ),
        )
      }

      assertEquals(listOf("wftr-003", "wftr-002"), store.listFeatureTaskRuntimeWorkflows(2).map { it.workflowId })
      assertEquals("wftr-003", store.latestFeatureTaskRuntimeWorkflow()?.workflowId)
      assertTrue(store.listFeatureImplementWorkflows(10).isEmpty())
      assertTrue(store.listFeatureVerifyWorkflows(10).isEmpty())
    }
  }

  @Test
  fun `workflow session summaries preserve started payload shape`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-sessions").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      insertFeatureImplementSession(connection)
      insertFeatureVerifySession(connection)

      val store = WorkflowStateStore(connection)
      val implementSummary = assertNotNull(store.getFeatureImplementSessionSummary("fis-session"))
      val verifySummary = assertNotNull(store.getFeatureVerifySessionSummary("fvr-session"))

      assertEquals(true, implementSummary.issueKeyProvided)
      assertEquals(listOf("markdown_file"), implementSummary.specInputTypes)
      assertEquals("workflow-runtime", implementSummary.featureName)
      assertEquals("Port workflow runtime", implementSummary.specSummary)
      assertEquals(4, verifySummary.acceptanceCriteriaCount)
      assertEquals(true, verifySummary.rolloutRelevant)
      assertEquals("Verify workflow runtime", verifySummary.specSummary)
    }
  }
}

private fun workerOwnership(
  workflowId: String,
  generation: Long,
  ownerToken: String,
): FeatureTaskRuntimeWorkerOwnership = FeatureTaskRuntimeWorkerOwnership(
  workflowId = workflowId,
  generation = generation,
  ownerToken = ownerToken,
  hostIdentity = "host-a",
  bootIdentity = "boot-a",
  pid = 1234,
  processBirthToken = "birth-1234",
  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  heartbeatAt = "2026-07-14T10:00:00Z",
  expiresAt = "2026-07-14T10:05:00Z",
  phaseId = "implement",
  phaseAttempt = 1,
)

private val taskRuntimeArtifactsJson: String =
  """
  {
    "feature_task_runtime_phase_records": {
      "plan": {
        "phase_id": "plan",
        "status": "completed",
        "attempt_count": 1,
        "started_at": "2026-06-02T10:00:00Z",
        "finished_at": "2026-06-02T10:01:30Z",
        "duration_millis": 90000,
        "resolved_agent_id": "agent-plan-1",
        "output_artifact": "{\"contract_version\":\"0.1\",\"plan\":\"ok\"}"
      }
    },
    "feature_task_runtime_phase_ledger": [
      {
        "action": "start",
        "sequence_number": 0,
        "timestamp": "2026-06-02T10:00:00Z",
        "phase_id": "plan",
        "attempt_count": 1,
        "resolved_agent_id": "agent-plan-1"
      },
      {
        "action": "complete",
        "sequence_number": 1,
        "timestamp": "2026-06-02T10:01:30Z",
        "phase_id": "plan",
        "attempt_count": 1,
        "resolved_agent_id": "agent-plan-1"
      }
    ]
  }
  """.trimIndent()

private fun insertFeatureImplementSession(connection: Connection) {
  connection.prepareStatement(
    """
    INSERT INTO feature_implement_sessions (
      session_id,
      issue_key_provided,
      issue_key_type,
      spec_input_types,
      spec_word_count,
      feature_size,
      feature_name,
      rollout_needed,
      acceptance_criteria_count,
      open_questions_count,
      spec_summary
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, "fis-session")
    statement.setInt(2, 1)
    statement.setString(3, "other")
    statement.setString(4, """["markdown_file"]""")
    statement.setInt(5, 123)
    statement.setString(6, "MEDIUM")
    statement.setString(7, "workflow-runtime")
    statement.setInt(8, 0)
    statement.setInt(9, 6)
    statement.setInt(10, 0)
    statement.setString(11, "Port workflow runtime")
    statement.executeUpdate()
  }
}

private fun insertFeatureVerifySession(connection: Connection) {
  connection.prepareStatement(
    """
    INSERT INTO feature_verify_sessions (
      session_id,
      acceptance_criteria_count,
      rollout_relevant,
      spec_summary
    ) VALUES (?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, "fvr-session")
    statement.setInt(2, 4)
    statement.setInt(3, 1)
    statement.setString(4, "Verify workflow runtime")
    statement.executeUpdate()
  }
}

private fun workflowRow(
  workflowId: String,
  sessionId: String,
  workflowName: String,
  currentStepId: String,
  mode: FeatureTaskWorkflowMode? = null,
): WorkflowStateRow = WorkflowStateRow(
  workflowId = workflowId,
  sessionId = sessionId,
  workflowName = workflowName,
  contractVersion = "0.1",
  workflowStatus = "running",
  currentStepId = currentStepId,
  stepsJson = "[]",
  artifactsJson = "{}",
  startedAt = null,
  updatedAt = null,
  finishedAt = null,
  mode = mode,
)

private fun goalChildWorkflow(workflowId: String, parentWorkflowId: String): WorkflowStateRow = workflowRow(
  workflowId = workflowId,
  sessionId = "ftr-$workflowId",
  workflowName = "bill-feature-task-runtime",
  currentStepId = "preplan",
  mode = FeatureTaskWorkflowMode.RUNTIME,
).copy(
  artifactsJson =
  """{"goal_continuation":{"issue_key":"SKILL-128","subtask_id":1,"parent_workflow_id":"$parentWorkflowId"}}""",
)

private fun goalChildIdentity(row: WorkflowStateRow): FeatureTaskExecutionIdentity = FeatureTaskExecutionIdentity(
  workflowId = row.workflowId,
  normalizedIssueKey = "SKILL-128",
  repositoryIdentity = "repo",
  governedSpecPath = ".feature-specs/SKILL-128/spec.md",
  mode = FeatureTaskWorkflowMode.RUNTIME,
  routeScope = FeatureTaskRouteScope.GOAL_CHILD,
)
