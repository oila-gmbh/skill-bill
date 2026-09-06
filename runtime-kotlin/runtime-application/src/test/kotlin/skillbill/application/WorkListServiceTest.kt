package skillbill.application
import skillbill.application.work.WorkListService
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkListServiceTest {
  @Test
  fun `work list invokes the workflow snapshot validation read seam before returning a workflow row`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      WorkflowStateRecord(
        workflowId = WorkflowId("wftr-invalid-snapshot"),
        sessionId = SessionId("ftr-117"),
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "running",
        currentStepId = "preplan",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = "2026-05-01T12:00:00Z",
        updatedAt = "2026-05-01T12:00:00Z",
        finishedAt = null,
      ),
    )
    val validator = object : WorkflowSnapshotValidator {
      override fun validate(snapshot: WorkflowStateSnapshot, slug: String): Unit =
        throw InvalidWorkflowStateSchemaError("Workflow '$slug' fails snapshot validation.")
    }
    val service = WorkListService(
      database = WorkListDatabase(
        workflows = workflows,
        work = listOf(
          WorkItem(
            issueKey = IssueKey("SKILL-117"),
            workflowKind = WorkItemKind.FEATURE_TASK_RUNTIME,
            workflowId = WorkflowId("wftr-invalid-snapshot"),
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

  @Test
  fun `work list batches workflow snapshot validation below SQLite bind limits`() {
    val delegate = InMemoryWorkflowStates()
    val workflows = BatchingWorkflowStates(delegate)
    val work = buildList {
      repeat(901) { index ->
        val workflowId = WorkflowId("wftr-batch-$index")
        delegate.saveFeatureTaskRuntimeWorkflow(
          WorkflowStateRecord(
            workflowId = workflowId,
            sessionId = SessionId("ftr-batch-$index"),
            workflowName = "bill-feature-task",
            contractVersion = "0.1",
            workflowStatus = "running",
            currentStepId = "implement",
            stepsJson = "[]",
            artifactsJson = "{}",
            startedAt = "2026-05-01T12:00:00Z",
            updatedAt = "2026-05-01T12:00:00Z",
            finishedAt = null,
          ),
        )
        add(
          WorkItem(
            issueKey = IssueKey("SKILL-117"),
            workflowKind = WorkItemKind.FEATURE_TASK_RUNTIME,
            workflowId = workflowId,
            startedAt = Instant.parse("2026-05-01T12:00:00Z"),
            currentState = "running",
            stateEnteredAt = Instant.parse("2026-05-01T12:00:00Z"),
            stateEnteredAtEstimated = false,
          ),
        )
      }
    }
    val service = WorkListService(
      database = WorkListDatabase(workflows = workflows, work = work),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val result = service.list()

    assertEquals(901, result.work.size)
    assertEquals(901, workflows.snapshotBatchSizes.sum())
    assertEquals(2, workflows.snapshotBatchSizes.size)
    assertEquals(true, workflows.snapshotBatchSizes.all { it <= 900 })
  }
}

private class WorkListDatabase(
  private val workflows: WorkflowStateRepository,
  private val work: List<WorkItem>,
) : DatabaseSessionFactory {
  override fun resolveDbPath(): Path = Path.of("/fake/work-list.db")

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWorkDefaults() {
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
    override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
    override val goalRunnerControls = EmptyGoalRunnerControlRepository
  }
}

private class BatchingWorkflowStates(
  private val delegate: WorkflowStateRepository,
) : WorkflowStateRepository by delegate {
  val snapshotBatchSizes = mutableListOf<Int>()

  override fun getFeatureTaskRuntimeWorkflows(workflowIds: Set<WorkflowId>): Map<WorkflowId, WorkflowStateRecord> {
    snapshotBatchSizes += workflowIds.size
    require(workflowIds.size <= 900) { "Snapshot lookup exceeds SQLite's bind limit." }
    return delegate.getFeatureTaskRuntimeWorkflows(workflowIds)
  }
}
