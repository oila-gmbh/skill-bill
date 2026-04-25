package skillbill.db

import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WorkflowStateStoreTest {
  @Test
  fun `feature implement workflow rows round trip with updated json payloads`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflows").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val initialRow =
        WorkflowStateRow(
          workflowId = "wfl-001",
          sessionId = "fis-001",
          workflowName = "bill-feature-implement",
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
          workflowName = "bill-feature-implement",
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
  fun `workflow lists and latest use updated timestamp then rowid ordering for both families`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-db-workflow-list").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)

      listOf("wfl-001", "wfl-002", "wfl-003").forEachIndexed { index, workflowId ->
        store.saveFeatureImplementWorkflow(
          workflowRow(
            workflowId = workflowId,
            sessionId = "fis-00$index",
            workflowName = "bill-feature-implement",
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
  fun `workflow session summaries preserve Python started payload shape`() {
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
)
