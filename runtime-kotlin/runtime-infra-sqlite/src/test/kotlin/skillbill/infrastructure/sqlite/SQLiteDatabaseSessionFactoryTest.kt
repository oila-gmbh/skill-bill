package skillbill.infrastructure.sqlite

import skillbill.model.RuntimeContext
import skillbill.ports.persistence.model.WorkflowStateRecord
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SQLiteDatabaseSessionFactoryTest {
  @Test
  fun `transaction rolls back repository writes when the use case fails`() {
    val tempDir = Files.createTempDirectory("skillbill-sqlite-session")
    val dbPath = tempDir.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(RuntimeContext(userHome = tempDir))

    assertFailsWith<IllegalStateException> {
      database.transaction(dbPath.toString()) { unitOfWork ->
        unitOfWork.workflowStates.saveFeatureImplementWorkflow(
          WorkflowStateRecord(
            workflowId = "wfl-rollback",
            sessionId = "fis-rollback",
            workflowName = "bill-feature-implement",
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
    val database = SQLiteDatabaseSessionFactory(RuntimeContext(userHome = tempDir))

    assertEquals(dbPath.toAbsolutePath().normalize(), database.resolveDbPath(dbPath.toString()))
    assertEquals(false, database.databaseExists(dbPath.toString()))
    database.read(dbPath.toString()) { Unit }
    assertEquals(true, database.databaseExists(dbPath.toString()))
  }
}
