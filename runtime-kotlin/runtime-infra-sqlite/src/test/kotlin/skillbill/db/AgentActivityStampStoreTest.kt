package skillbill.db

import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentActivityStampStoreTest {
  @Test
  fun `record then read returns stamp from fresh connection`() {
    val tempDir = Files.createTempDirectory("agent-activity-store")
    val factory = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = tempDir))
    val dbPath = factory.resolveDbPath()
    val workflowId = "wfl-activity-1"
    val stamp = AgentActivityStamp(
      recordedAt = Instant.parse("2026-08-30T10:00:00Z"),
      label = AgentActivityLabel.STDOUT,
    )
    factory.selfManagedWrite { unitOfWork ->
      unitOfWork.agentActivityStamps.record(workflowId, stamp)
    }
    factory.read { unitOfWork ->
      val read = unitOfWork.agentActivityStamps.read(workflowId)
      assertEquals(stamp, read)
      assertNull(unitOfWork.agentActivityStamps.read("missing"))
    }
  }
}
