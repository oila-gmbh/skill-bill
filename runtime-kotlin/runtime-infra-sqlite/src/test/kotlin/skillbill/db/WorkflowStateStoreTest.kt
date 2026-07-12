package skillbill.db

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.DbConstants
import skillbill.db.workflow.WorkflowStateRow
import skillbill.db.workflow.WorkflowStateStore
import skillbill.error.InvalidWorkflowStateSchemaError
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
