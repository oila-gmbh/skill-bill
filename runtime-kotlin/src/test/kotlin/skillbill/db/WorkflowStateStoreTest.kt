package skillbill.db

import java.nio.file.Files
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
}
