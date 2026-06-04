package skillbill.application

import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F-001 / F-008: unit coverage for [FeatureTaskRuntimeStatusService]'s projection
 * branches that the CLI surface cannot reach directly:
 *
 *  - a workflow row that exists but has NO per-phase records yet projects status
 *    `ok` with every phase pending and the first phase as current (distinct from
 *    the null/not_found case);
 *  - a phase whose newest append-only ledger entry is BLOCKED is reclassified as
 *    blocked even though the runner leaves its durable per-phase record at
 *    `running` (the runner never persists a blocked per-phase record).
 *
 * Uses a minimal in-memory recorder seam so the projection is exercised against
 * real persisted artifacts without a database.
 */
class FeatureTaskRuntimeStatusServiceTest {
  @Test
  fun `null projection for an unknown workflow id`() {
    val harness = statusHarness()

    assertNull(harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = "wftr-missing")))
  }

  @Test
  fun `workflow with no phase records projects every phase pending`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals(0, projection.completeCount)
    assertEquals(6, projection.pendingCount)
    assertEquals(0, projection.blockedCount)
    assertEquals("preplan", projection.currentPhaseId)
    assertEquals(
      listOf("pending", "pending", "pending", "pending", "pending", "pending"),
      projection.phases.map { it.status },
    )
  }

  @Test
  fun `phase whose latest ledger entry is blocked is reported blocked and current`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    // The runner records a block only in the ledger, leaving the per-phase record at
    // `running`; status must derive the blocked state from the newest ledger entry.
    harness.recordRunning("implement", attemptCount = 3)
    harness.recordCompleted("preplan", attemptCount = 1)
    harness.recordCompleted("plan", attemptCount = 1)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.START, "implement", attemptCount = 1)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.BLOCKED, "implement", attemptCount = 3)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals(2, projection.completeCount)
    assertEquals(1, projection.blockedCount)
    assertEquals("implement", projection.currentPhaseId)
    assertEquals("completed", projection.phases.single { it.phaseId == "plan" }.status)
    assertEquals("blocked", projection.phases.single { it.phaseId == "implement" }.status)
  }

  @Test
  fun `a later resume entry supersedes an earlier block`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recordRunning("implement", attemptCount = 4)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.BLOCKED, "implement", attemptCount = 3)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.RESUME, "implement", attemptCount = 4)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals(0, projection.blockedCount)
    assertEquals("running", projection.phases.single { it.phaseId == "implement" }.status)
  }

  @Test
  fun `phase with a durable blocked record is reported blocked even when the ledger has no blocked entry`() {
    // F-002: blocked-ness derives primarily from the durable per-phase record, so it survives even
    // when the append-only ledger's BLOCKED entry has been pruned by the retention cap.
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recordCompleted("preplan", attemptCount = 1)
    harness.recordCompleted("plan", attemptCount = 1)
    harness.recordBlocked("implement", attemptCount = 3, "fix loop exhausted")
    // Ledger carries only a START (the BLOCKED entry was pruned away); the durable record stands.
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.START, "implement", attemptCount = 1)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals(1, projection.blockedCount)
    assertEquals("blocked", projection.phases.single { it.phaseId == "implement" }.status)
    assertEquals("implement", projection.currentPhaseId)
  }

  @Test
  fun `projection surfaces the durable resolved feature branch`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordResolvedBranch(
      WORKFLOW_ID,
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch(
        branch = "feat/SKILL-65-runtime-feature-task-parity",
        baseBranch = "main",
        created = true,
      ),
    )

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals("feat/SKILL-65-runtime-feature-task-parity", projection.resolvedBranch)
  }

  @Test
  fun `projection resolved branch is null before branch setup`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertNull(projection.resolvedBranch)
  }

  private fun statusHarness(): StatusHarness {
    val repository = StatusInMemoryWorkflowRepository()
    val database = StatusFakeDatabaseSessionFactory(repository)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, StatusNoopSnapshotValidator)
    return StatusHarness(recorder, FeatureTaskRuntimeStatusService(recorder))
  }

  private class StatusHarness(
    val recorder: FeatureTaskRuntimePhaseRecorder,
    val service: FeatureTaskRuntimeStatusService,
  ) {
    fun recordRunning(phaseId: String, attemptCount: Int) = recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "running",
        attemptCount = attemptCount,
        resolvedAgentId = "claude",
        finished = false,
        outputArtifact = null,
      ),
    )

    fun recordCompleted(phaseId: String, attemptCount: Int) = recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "completed",
        attemptCount = attemptCount,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
      ),
    )

    fun recordBlocked(phaseId: String, attemptCount: Int, blockedReason: String) = recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "blocked",
        attemptCount = attemptCount,
        resolvedAgentId = "claude",
        finished = false,
        outputArtifact = null,
        blockedReason = blockedReason,
      ),
    )

    fun recordLedger(action: FeatureTaskRuntimePhaseLedgerAction, phaseId: String, attemptCount: Int) =
      recorder.appendLedgerEntry(
        FeatureTaskRuntimePhaseLedgerRequest(
          workflowId = WORKFLOW_ID,
          action = action,
          phaseId = phaseId,
          attemptCount = attemptCount,
          resolvedAgentId = "claude",
          blockedReason = if (action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED) "fix loop exhausted" else null,
        ),
      )
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-20260603-status-0001"
    const val SESSION_ID = "ftr-status-001"
  }
}

private class StatusFakeDatabaseSessionFactory(
  private val repository: StatusInMemoryWorkflowRepository,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/status-metrics.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@StatusFakeDatabaseSessionFactory.dbPath
    override val reviews: ReviewRepository get() = error("unused")
    override val learnings: LearningRepository get() = error("unused")
    override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused")
    override val workflowStates: WorkflowStateRepository = repository
  }
}

private class StatusInMemoryWorkflowRepository : WorkflowStateRepository {
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) = Unit

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null
}

private object StatusNoopSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
}
