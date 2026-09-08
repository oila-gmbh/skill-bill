package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifactKeys
import skillbill.contracts.JsonSupport
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttemptLedgerWorkflowDecodingTest {
  @Test
  fun `progress token fingerprints artifacts without embedding the body`() {
    val bloatedArtifacts = JsonSupport.mapToJsonString(
      mapOf("feature_task_runtime_delivered_projections" to "x".repeat(50_000)),
    )
    val snapshot = progressSnapshot(artifactsJson = bloatedArtifacts)

    val token = snapshot.progressToken()

    assertFalse(token.contains("xxxx"))
    assertTrue(token.length < 2_000)
    assertTrue(artifactsFingerprint(bloatedArtifacts) in token)
  }

  @Test
  fun `progress token changes when only artifacts mutate`() {
    val before = progressSnapshot(artifactsJson = """{"progress_event":{"summary":"a"}}""")
    val after = progressSnapshot(artifactsJson = """{"progress_event":{"summary":"b"}}""")

    assertNotEquals(before.progressToken(), after.progressToken())
  }

  @Test
  fun `decodeArtifactKeys materializes only requested top-level keys`() {
    val artifactsJson = JsonSupport.mapToJsonString(
      mapOf(
        GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY to mapOf(
          "event_kind" to "operation_heartbeat",
          "workflow_id" to "wfl-child",
        ),
        "feature_task_runtime_delivered_projections" to mapOf(
          "bloat" to "y".repeat(20_000),
        ),
        "progress_event" to mapOf("summary" to "alive"),
      ),
    )

    val sparse = decodeArtifactKeys(
      artifactsJson,
      setOf(GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY, "progress_event"),
    )

    assertEquals(setOf(GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY, "progress_event"), sparse.keys)
    assertEquals("alive", (sparse["progress_event"] as Map<*, *>)["summary"])
    assertFalse(sparse.containsKey("feature_task_runtime_delivered_projections"))
  }

  private fun progressSnapshot(artifactsJson: String): WorkflowStateSnapshot = WorkflowStateSnapshot(
    workflowId = "wfl-child",
    sessionId = "session-1",
    workflowName = "bill-feature-task",
    contractVersion = "1.0",
    workflowStatus = "running",
    currentStepId = "validate",
    stepsJson = """[{"step_id":"validate","status":"running","attempt_count":1}]""",
    artifactsJson = artifactsJson,
    startedAt = "2026-06-02T10:00:00Z",
    updatedAt = "2026-06-02T10:00:01Z",
    finishedAt = null,
  )
}
