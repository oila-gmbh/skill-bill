package skillbill.ports.persistence

import java.nio.file.Path

interface DatabaseSessionFactory {
  fun resolveDbPath(dbOverride: String? = null): Path

  fun databaseExists(dbOverride: String? = null): Boolean

  fun <T> read(dbOverride: String? = null, block: (UnitOfWork) -> T): T

  fun <T> transaction(dbOverride: String? = null, block: (UnitOfWork) -> T): T
}

interface UnitOfWork {
  val dbPath: Path
  val reviews: ReviewRepository
  val learnings: LearningRepository
  val lifecycleTelemetry: LifecycleTelemetryRepository
  val telemetryOutbox: TelemetryOutboxRepository
  val workflowStates: WorkflowStateRepository
}
