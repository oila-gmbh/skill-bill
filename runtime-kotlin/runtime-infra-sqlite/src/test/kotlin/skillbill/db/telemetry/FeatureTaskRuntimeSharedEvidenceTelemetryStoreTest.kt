package skillbill.db.telemetry

import skillbill.db.core.DatabaseRuntime
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeSharedEvidenceTelemetryStoreTest {
  @Test
  fun `shared evidence measurement is enqueued under the new event name with its bounded payload`() {
    val dbPath = Files.createTempFile("shared-evidence-telemetry", ".db").toString()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = LifecycleTelemetryStore(connection)
      val record = FeatureTaskRuntimeSharedEvidenceMeasurement(
        workflowId = "wftr-1",
        checkpointFingerprint = "fp-1",
        consumerPhaseId = "audit",
        outcome = FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION,
        fileIndexCount = 2,
        hunkIndexCount = 4,
      )

      store.featureTaskRuntimeSharedEvidence(record)

      val row = connection.prepareStatement(
        "SELECT event_name, payload_json FROM telemetry_outbox ORDER BY id DESC LIMIT 1",
      ).use { statement ->
        statement.executeQuery().use { rs ->
          require(rs.next())
          rs.getString("event_name") to rs.getString("payload_json")
        }
      }
      assertEquals("skillbill_feature_task_runtime_shared_evidence", row.first)
      assertTrue(row.second.contains("\"outcome\":\"derivation\""), row.second)
      assertTrue(row.second.contains("\"checkpoint_fingerprint\":\"fp-1\""), row.second)
      assertTrue(row.second.contains("\"file_index_count\":2"), row.second)
      assertTrue(!row.second.contains("diff --"), row.second)
    }
  }
}
