package skillbill.application

import skillbill.application.work.WorkListService
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkListRepository
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkItemKind
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.WorkflowSnapshotValidator
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkListServiceTest {
  @Test
  fun `work list invokes the workflow snapshot validation read seam before returning a workflow row`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      WorkflowStateRecord(
        workflowId = "wfl-invalid-snapshot",
        sessionId = "fis-117",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "running",
        currentStepId = "assess",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = "2026-05-01T12:00:00Z",
        updatedAt = "2026-05-01T12:00:00Z",
        finishedAt = null,
      ),
    )
    val validator = object : WorkflowSnapshotValidator {
      override fun validate(snapshot: Map<String, Any?>, slug: String): Unit =
        throw InvalidWorkflowStateSchemaError("Workflow '$slug' fails snapshot validation.")
    }
    val service = WorkListService(
      database = WorkListDatabase(
        workflows = workflows,
        work = listOf(
          WorkItem(
            issueKey = "SKILL-117",
            workflowKind = WorkItemKind.FEATURE_TASK_PROSE,
            workflowId = "wfl-invalid-snapshot",
            startedAt = Instant.parse("2026-05-01T12:00:00Z"),
            currentState = "running",
            stateEnteredAt = Instant.parse("2026-05-01T12:00:00Z"),
            stateEnteredAtEstimated = false,
          ),
        ),
      ),
      workflowSnapshotValidator = validator,
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> { service.list() }
  }
}

private class WorkListDatabase(
  private val workflows: InMemoryWorkflowStates,
  private val work: List<WorkItem>,
) : DatabaseSessionFactory {
  override fun resolveDbPath(dbOverride: String?): Path = Path.of("/fake/work-list.db")

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = Path.of("/fake/work-list.db")
    override val workflowStates = workflows
    override val workList: WorkListRepository = object : WorkListRepository {
      override fun list(limit: Int?): List<WorkItem> = limit?.let(work::take) ?: work
    }
    override val learnings: LearningRepository
      get() = error("Not exercised by WorkListServiceTest.")
    override val reviews: ReviewRepository
      get() = error("Not exercised by WorkListServiceTest.")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("Not exercised by WorkListServiceTest.")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("Not exercised by WorkListServiceTest.")
    override val telemetryOutbox: TelemetryOutboxRepository
      get() = error("Not exercised by WorkListServiceTest.")
  }
}
