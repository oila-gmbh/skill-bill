package skillbill.db

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.DbConstants
import skillbill.db.workflow.WorkflowStateRow
import skillbill.db.workflow.WorkflowStateStore
import skillbill.error.InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.ProseFeatureTaskWorkflowWriteRefusedError
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowStateStoreTest {
  @Test
  fun `identity-less goal parent is excluded from standalone candidates so lookup can reach goal continuation`() {
    val dbPath = Files.createTempDirectory("standalone-candidate-goal-parent").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      // Goal parents carry no execution identity by design. Since the prose engine was retired every
      // row is mode=runtime, so mode must never be what readmits a parent into standalone discovery:
      // doing so reports needs_identity_repair for a goal and repair-identity would stamp
      // route_scope=standalone on it.
      store.saveFeatureTaskRuntimeWorkflow(
        workflowRow(
          "wftr-goal-parent",
          "ftr-goal",
          "bill-feature-task",
          "plan",
          FeatureTaskWorkflowMode.RUNTIME,
        )
          .copy(
            issueKey = "SKILL-128",
            workflowStatus = "paused",
            artifactsJson = """{"plan":{"mode":"decompose"},"decomposition_runtime":{"issue_key":"SKILL-128"}}""",
          ),
      )
      store.saveFeatureTaskRuntimeWorkflow(
        workflowRow("wftr-legacy", "ftr-legacy", "bill-feature-task", "plan", FeatureTaskWorkflowMode.RUNTIME)
          .copy(issueKey = "SKILL-128", workflowStatus = "paused"),
      )

      val candidates = store.findStandaloneFeatureTaskCandidates("SKILL-128", "repo")

      assertEquals(listOf("wftr-legacy"), candidates.map { it.workflow.workflowId })
    }
  }

  @Test
  fun `feature task execution identity can be read exactly at adoption boundary`() {
    val dbPath = Files.createTempDirectory("goal-child-identity-read").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val workflow = goalChildWorkflow("wftr-child", "wftr-parent")
      val identity = goalChildIdentity(workflow)

      store.saveFeatureTaskRuntimeWorkflow(workflow)
      store.saveFeatureTaskExecutionIdentity(identity)

      assertEquals(identity, store.getFeatureTaskExecutionIdentity(identity.workflowId))
      assertEquals(null, store.getFeatureTaskExecutionIdentity("wftr-missing"))
    }
  }

  @Test
  fun `hard reset deletion removes only goal children owned by the parent workflow`() {
    val dbPath = Files.createTempDirectory("goal-child-reset").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val target = goalChildWorkflow("wftr-target", "wftr-parent")
      val siblingGoal = goalChildWorkflow("wftr-other-goal", "wftr-other-parent")
      val standalone = goalChildWorkflow("wftr-standalone", "wftr-parent")
      listOf(target, siblingGoal, standalone).forEach(store::saveFeatureTaskRuntimeWorkflow)
      listOf(target, siblingGoal).forEach { row -> store.saveFeatureTaskExecutionIdentity(goalChildIdentity(row)) }
      store.saveFeatureTaskExecutionIdentity(
        goalChildIdentity(standalone).copy(routeScope = FeatureTaskRouteScope.STANDALONE),
      )

      assertEquals(1, store.deleteGoalChildWorkflowsByParent("wftr-parent"))

      assertEquals(null, store.getFeatureTaskRuntimeWorkflow(target.workflowId))
      assertNotNull(store.getFeatureTaskRuntimeWorkflow(siblingGoal.workflowId))
      assertNotNull(store.getFeatureTaskRuntimeWorkflow(standalone.workflowId))
    }
  }

  @Test
  fun `expired-lease non-terminal row is a crash-reconciliation candidate but live-lease and terminal are not`() {
    val dbPath = Files.createTempDirectory("crash-reconcile-candidates").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      // Expired-lease running row: a candidate.
      seedRunningRowWithLease(store, "wftr-expired", "owner-token-expired1", expiresAt = "2026-07-14T10:05:00Z")
      // Live-lease running row: not a candidate.
      seedRunningRowWithLease(store, "wftr-live", "owner-token-live00001", expiresAt = "2999-01-01T00:00:00Z")
      // Terminal row with no lease: not a candidate.
      store.saveFeatureTaskRuntimeWorkflow(
        workflowRow("wftr-done", "ftr-done", "bill-feature-task", "implement", FeatureTaskWorkflowMode.RUNTIME)
          .copy(workflowStatus = "completed"),
      )

      val candidates = store.findFeatureTaskRuntimeCrashReconciliationCandidates("2026-07-14T10:06:00Z")

      assertEquals(listOf("wftr-expired"), candidates.map { it.ownership.workflowId })
      assertEquals("implement", candidates.single().currentStepId)
    }
  }

  @Test
  fun `crash reconcile transitions the row to pending, releases the lease, and keeps phase and artifacts`() {
    val dbPath = Files.createTempDirectory("crash-reconcile-write").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = workflowRow(
        "wftr-crash",
        "ftr-crash",
        "bill-feature-task",
        "implement",
        FeatureTaskWorkflowMode.RUNTIME,
      ).copy(workflowStatus = "running", artifactsJson = """{"phase_records":{"preplan":"done"}}""")
      store.saveFeatureTaskRuntimeWorkflow(row)
      val updatedAt = assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-crash0001")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))

      val reconciled = store.reconcileFeatureTaskRuntimeCrashedWorker(
        workflowId = row.workflowId,
        ownerToken = ownership.ownerToken,
        generation = ownership.generation,
        interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
        nowInstant = "2026-07-14T10:06:00Z",
      )

      assertTrue(reconciled)
      val after = assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId))
      assertEquals("pending", after.workflowStatus)
      assertEquals("implement", after.currentStepId)
      assertEquals(row.artifactsJson, after.artifactsJson)
      assertEquals(null, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
      assertEquals(
        "lease_expired: worker lease expired and process confirmed dead",
        interruptionReasonOf(connection, row.workflowId),
      )
      // Idempotent: a second pass finds no candidate and changes nothing.
      assertTrue(store.findFeatureTaskRuntimeCrashReconciliationCandidates("2026-07-14T10:06:00Z").isEmpty())
    }
  }

  @Test
  fun `crash reconcile is rejected when owner_token or generation fencing no longer matches`() {
    val dbPath = Files.createTempDirectory("crash-reconcile-fencing").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = workflowRow(
        "wftr-fence",
        "ftr-fence",
        "bill-feature-task",
        "implement",
        FeatureTaskWorkflowMode.RUNTIME,
      ).copy(workflowStatus = "running")
      store.saveFeatureTaskRuntimeWorkflow(row)
      val updatedAt = assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).updatedAt
      val ownership = workerOwnership(row.workflowId, generation = 1, ownerToken = "owner-token-fence0001")
      assertTrue(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))

      val wrongToken = store.reconcileFeatureTaskRuntimeCrashedWorker(
        row.workflowId,
        "owner-token-stale0001",
        1,
        "reason",
        "2026-07-14T10:06:00Z",
      )
      val wrongGeneration = store.reconcileFeatureTaskRuntimeCrashedWorker(
        row.workflowId,
        ownership.ownerToken,
        99,
        "reason",
        "2026-07-14T10:06:00Z",
      )

      assertEquals(false, wrongToken)
      assertEquals(false, wrongGeneration)
      assertEquals("running", assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).workflowStatus)
      assertEquals(ownership, store.getFeatureTaskRuntimeWorkerOwnership(row.workflowId))
    }
  }

  @Test
  fun `runtime worker ownership is acquired fenced transferred heartbeated and released`() {
    val dbPath = Files.createTempDirectory("runtime-worker-lease").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = workflowRow(
        workflowId = "wftr-worker",
        sessionId = "ftr-worker",
        workflowName = "bill-feature-task",
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
        workflowId = "wftr-contention",
        sessionId = "ftr-contention",
        workflowName = "bill-feature-task",
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
        workflowId = "wftr-invalid-lease",
        sessionId = "ftr-invalid-lease",
        workflowName = "bill-feature-task",
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
          workflowId = "wftr-terminal",
          sessionId = "ftr-terminal",
          workflowName = "bill-feature-task",
          currentStepId = "preplan",
          mode = FeatureTaskWorkflowMode.RUNTIME,
        )

      store.saveFeatureTaskRuntimeWorkflow(initialRow)
      store.saveFeatureTaskRuntimeWorkflow(
        initialRow.copy(
          workflowStatus = "abandoned",
          currentStepId = "pr",
          finishedAt = "",
        ),
      )

      val saved = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-terminal"))
      assertEquals("abandoned", saved.workflowStatus)
      assertNotNull(saved.finishedAt)
    }
  }

  /**
   * SKILL-141 Subtask 1 AC-007: the paused decomposed-goal parent must survive a persistence
   * round-trip under its own id and stay non-terminal — no finished timestamp is stamped, so a
   * later resume still reads it as open work.
   */
  @Test
  fun `paused parent workflow round-trips under the same id and is not stamped finished`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-paused").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val initialRow = workflowRow(
        workflowId = "wftr-paused-parent",
        sessionId = "ftr-paused-parent",
        workflowName = "bill-feature-task",
        currentStepId = "plan",
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ).copy(artifactsJson = """{"plan":{"mode":"decompose"}}""")

      store.saveFeatureTaskRuntimeWorkflow(initialRow)
      store.saveFeatureTaskRuntimeWorkflow(initialRow.copy(workflowStatus = "paused", finishedAt = null))

      val saved = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-paused-parent"))
      assertEquals("wftr-paused-parent", saved.workflowId)
      assertEquals("paused", saved.workflowStatus)
      assertEquals("plan", saved.currentStepId)
      assertEquals("""{"plan":{"mode":"decompose"}}""", saved.artifactsJson)
      assertEquals(null, saved.finishedAt)
    }
  }
}

class WorkflowStateStoreLifecycleTest {
  @Test
  fun `workflow state entry starts at supplied start time and changes only on a status transition`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-state-entry").resolve("metrics.db")
    val startedAt = "2999-05-01T12:00:00.123456789Z"

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val initial = workflowRow(
        workflowId = "wftr-state-entry-main",
        sessionId = "ftr-state-entry-main",
        workflowName = "bill-feature-task",
        currentStepId = "preplan",
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ).copy(startedAt = startedAt)

      store.saveFeatureTaskRuntimeWorkflow(initial)
      val inserted = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-state-entry-main"))
      assertEquals(startedAt, inserted.startedAt)
      assertEquals(startedAt, inserted.stateEnteredAt)
      assertEquals(false, inserted.stateEnteredAtEstimated)

      store.saveFeatureTaskRuntimeWorkflow(initial.copy(currentStepId = "plan", artifactsJson = "{\"plan\":{}}"))
      val sameStatus = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-state-entry-main"))
      assertEquals(startedAt, sameStatus.stateEnteredAt)
      assertEquals(false, sameStatus.stateEnteredAtEstimated)

      store.saveFeatureTaskRuntimeWorkflow(sameStatus.copy(workflowStatus = "blocked", currentStepId = "plan"))
      val transitioned = assertNotNull(store.getFeatureTaskRuntimeWorkflow("wftr-state-entry-main"))
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
      workflowId = "wftr-concurrent-state-entry",
      sessionId = "ftr-concurrent-state-entry",
      workflowName = "bill-feature-task",
      currentStepId = "preplan",
      mode = FeatureTaskWorkflowMode.RUNTIME,
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
            WorkflowStateStore(connection).saveFeatureTaskRuntimeWorkflow(
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

  @Test
  fun `workflow lists and latest use updated timestamp then rowid ordering for both families`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)

      listOf("wftr-001", "wftr-002", "wftr-003").forEachIndexed { index, workflowId ->
        store.saveFeatureTaskRuntimeWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "ftr-00$index",
            workflowName = "bill-feature-task",
            currentStepId = "preplan",
            mode = FeatureTaskWorkflowMode.RUNTIME,
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

      assertEquals(listOf("wftr-003", "wftr-002"), store.listFeatureTaskRuntimeWorkflows(2).map { it.workflowId })
      assertEquals("wftr-003", store.latestFeatureTaskRuntimeWorkflow()?.workflowId)
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
      assertEquals("bill-feature", saved.implementationSkill)
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
  fun `audit repair artifact round trips through sqlite without rewriting its contract identity`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-audit-repair").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = workflowRow(
        workflowId = "wftr-audit-repair",
        sessionId = "ftr-audit-repair",
        workflowName = "bill-feature-task",
        currentStepId = "implement",
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ).copy(artifactsJson = auditRepairArtifactsJson())

      store.saveFeatureTaskRuntimeWorkflow(row)

      assertEquals(
        auditRepairArtifactsJson(),
        assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).artifactsJson,
      )

      store.saveFeatureTaskRuntimeWorkflow(row.copy(artifactsJson = auditRepairArtifactsJson("9.9")))
      assertEquals(
        auditRepairArtifactsJson("9.9"),
        assertNotNull(store.getFeatureTaskRuntimeWorkflow(row.workflowId)).artifactsJson,
        "SQLite must preserve incompatible identity for production mapping to reject at runtime use.",
      )
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

  /**
   * SKILL-175 subtask 6 AC-002 quarantine proof. The write path refuses `mode='prose'` writes, so the
   * ONLY way a genuine legacy prose row exists in the database is one that was persisted before the
   * engine was retired (or is inserted directly, as done here). Such a row must remain readable by the
   * store read path and must NEVER be silently reinterpreted as a runtime row: reads surface it as
   * PROSE, and a runtime-mode read of it loud-fails rather than coercing it. This is the only test in
   * the suite permitted to carry `mode='prose'` product tokens.
   */
  @Test
  fun `quarantined legacy prose rows stay readable but are never silently routed as runtime`() {
    val dbPath = Files.createTempDirectory("legacy-prose-quarantine").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, session_id, workflow_name, mode, implementation_skill, contract_version,
          workflow_status, current_step_id, steps_json, artifacts_json, issue_key,
          started_at, updated_at, state_entered_at, state_entered_at_estimated, finished_at
        ) VALUES (?, ?, 'bill-feature-task', 'prose', 'bill-feature-task-prose', ?, 'running', 'plan',
                  '[]', '{}', 'SKILL-901', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, NULL)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "wfl-legacy-prose-001")
        statement.setString(2, "fis-legacy-prose-001")
        statement.setString(3, DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION)
        statement.executeUpdate()
      }

      val store = WorkflowStateStore(connection)

      // (a) The prose row stays readable via the store read path, still decoded as prose.
      val prose = assertNotNull(store.getFeatureImplementWorkflow("wfl-legacy-prose-001"))
      assertEquals(FeatureTaskWorkflowMode.PROSE, prose.mode)
      assertEquals("bill-feature-task-prose", prose.implementationSkill)
      assertEquals(DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION, prose.contractVersion)

      // (b) Reads never silently reinterpret it as runtime: the generic feature-task read surfaces the
      // same PROSE row, and a runtime-mode read loud-fails instead of coercing it.
      val generic = assertNotNull(store.getFeatureTaskWorkflow("wfl-legacy-prose-001"))
      assertEquals(FeatureTaskWorkflowMode.PROSE, generic.mode)
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        store.getFeatureTaskRuntimeWorkflow("wfl-legacy-prose-001")
      }

      // The write path refuses any prose write, so the row can never be re-created through the store.
      assertFailsWith<ProseFeatureTaskWorkflowWriteRefusedError> {
        store.saveFeatureImplementWorkflow(prose)
      }
    }
  }

  @Test
  fun `terminalizeLegacyProseFeatureTaskWorkflow preserves mode and records status artifacts`() {
    val dbPath = Files.createTempDirectory("legacy-prose-terminalize").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, session_id, workflow_name, mode, implementation_skill, contract_version,
          workflow_status, current_step_id, steps_json, artifacts_json, issue_key,
          started_at, updated_at, state_entered_at, state_entered_at_estimated, finished_at
        ) VALUES (?, ?, 'bill-feature-task', 'prose', 'bill-feature-task-prose', ?, 'paused', 'assess',
                  '[{"step_id":"assess","status":"completed"}]', '{"history_note":"retain-me"}', 'SKILL-179',
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, NULL)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "wfl-legacy-prose-term-001")
        statement.setString(2, "fis-legacy-prose-term-001")
        statement.setString(3, DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION)
        statement.executeUpdate()
      }

      val store = WorkflowStateStore(connection)
      store.terminalizeLegacyProseFeatureTaskWorkflow(
        requireNotNull(store.getFeatureTaskWorkflow("wfl-legacy-prose-term-001")).copy(
          workflowStatus = "abandoned",
          artifactsJson =
          """{"history_note":"retain-me",""" +
            """"operator_abandonment":{"reason":"retire","abandoned_at":"2026-08-09T00:00:00Z"}}""",
          finishedAt = "2026-08-09T00:00:00Z",
        ),
      )

      val saved = assertNotNull(store.getFeatureTaskWorkflow("wfl-legacy-prose-term-001"))
      assertEquals(FeatureTaskWorkflowMode.PROSE, saved.mode)
      assertEquals("abandoned", saved.workflowStatus)
      assertContains(saved.artifactsJson, "retain-me")
      assertContains(saved.artifactsJson, "operator_abandonment")
      assertFailsWith<ProseFeatureTaskWorkflowWriteRefusedError> {
        store.saveFeatureImplementWorkflow(saved)
      }
    }
  }
}
