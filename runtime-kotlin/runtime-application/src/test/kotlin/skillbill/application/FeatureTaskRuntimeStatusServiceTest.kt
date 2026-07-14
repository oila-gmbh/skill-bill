package skillbill.application

import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.featuretask.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.agentAttributionFromPhaseState
import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.BLOCKED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.RESUME
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.START
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
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
    harness.recordRunInvariants(FeatureTaskRuntimeFeatureSize.LARGE)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals("LARGE", projection.featureSize)
    assertEquals(0, projection.completeCount)
    // Ten stepIds including the loop-only implement_fix (SKILL-85 M1).
    assertEquals(10, projection.pendingCount)
    assertEquals(0, projection.blockedCount)
    assertEquals("preplan", projection.currentPhaseId)
    assertEquals(List(10) { "pending" }, projection.phases.map { it.status })
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
  fun `ledger-only audit gap projects reopened implement as current`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "review", "audit")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recordLoopEdge(
      phaseId = "implement",
      attemptCount = 1,
      loopId = "audit_gap",
      edgeIteration = 1,
    )

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals("implement", projection.currentPhaseId)
  }

  @Test
  fun `ledger-only review fix projects implement fix as current`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "review")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recordLoopEdge(
      phaseId = "implement_fix",
      attemptCount = 1,
      loopId = "review_fix",
      edgeIteration = 1,
    )

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals("implement_fix", projection.currentPhaseId)
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

  @Test
  fun `projection surfaces the durable decompose terminal with subtask count and guidance fields`() {
    // T-F001 / AC4: a durable decompose-terminal record projects into the status as a non-null
    // decomposeTerminal carrying the reason, manifest/subtask paths, and the derived subtask count.
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.decomposeTerminalRecorder.recordDecomposeTerminal(
      WORKFLOW_ID,
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal(
        reason = "Plan needs ordered subtasks.",
        parentSpecPath = ".feature-specs/SKILL-65-runtime/spec.md",
        decompositionManifestPath = ".feature-specs/SKILL-65-runtime/decomposition-manifest.yaml",
        subtaskSpecPaths = listOf(
          ".feature-specs/SKILL-65-runtime/spec_subtask_1_domain.md",
          ".feature-specs/SKILL-65-runtime/spec_subtask_2_runtime.md",
        ),
      ),
    )

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    val terminal = requireNotNull(projection.decomposeTerminal)
    assertEquals("Plan needs ordered subtasks.", terminal.reason)
    assertEquals(".feature-specs/SKILL-65-runtime/decomposition-manifest.yaml", terminal.decompositionManifestPath)
    assertEquals(
      listOf(
        ".feature-specs/SKILL-65-runtime/spec_subtask_1_domain.md",
        ".feature-specs/SKILL-65-runtime/spec_subtask_2_runtime.md",
      ),
      terminal.subtaskSpecPaths,
    )
    assertEquals(2, terminal.subtaskCount)
    assertEquals(0, projection.pendingCount)
    assertEquals(0, projection.blockedCount)
    assertNull(projection.currentPhaseId)
  }

  @Test
  fun `projection decompose terminal is null when no decompose stop was recorded`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertNull(projection.decomposeTerminal)
  }

  @Test
  fun `projection feature size is null before run invariants are persisted`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertNull(projection.featureSize)
  }

  @Test
  fun `fully forward completed run does not project the loop-only implement_fix as current`() {
    // SKILL-85 Subtask 4 (F-005/F-006): a clean forward run completes every phase except the loop-only
    // implement_fix (never launched on a clean run, so permanently pending). currentPhaseId must skip
    // it rather than report a never-run loop-only phase; with no other incomplete phase it reports none.
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "review", "audit", "validate", "write_history", "commit_push", "pr")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals(9, projection.completeCount)
    assertEquals("pending", projection.phases.single { it.phaseId == "implement_fix" }.status)
    assertNull(projection.currentPhaseId, "a completed forward run reports no current phase, not implement_fix")
  }

  @Test
  fun `attribution rolls up a single-agent run to participating equals finalizer`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recordCompleted("implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(COMPLETE, "commit_push", attemptCount = 1, resolvedAgentId = "codex")

    val attribution = harness.attribution()

    assertEquals("codex", attribution.finalizingAgentId)
    assertEquals(listOf("codex"), attribution.participatingAgentIds)
  }

  @Test
  fun `attribution rolls up a multi-agent recovery handoff to order-stable participants and resuming finalizer`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    // codex starts, hits a limit; claude resumes and completes.
    // A phase record for the completed implement phase carries claude as its resolvedAgentId,
    // exercising the phase-record sweep path in agentAttributionFromPhaseState.
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(RESUME, "implement", attemptCount = 2, resolvedAgentId = "claude")
    harness.recordCompleted("implement", attemptCount = 2, resolvedAgentId = "claude")
    harness.recordLedger(COMPLETE, "commit_push", attemptCount = 1, resolvedAgentId = "claude")

    val attribution = harness.attribution()

    assertEquals("claude", attribution.finalizingAgentId)
    // codex contributed via ledger (START); claude contributed via both ledger (RESUME/COMPLETE) and phase record.
    assertEquals(listOf("codex", "claude"), attribution.participatingAgentIds)
  }

  @Test
  fun `attribution finalizer is the terminal blocked ledger entry`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(BLOCKED, "review", attemptCount = 3, resolvedAgentId = "claude")

    val attribution = harness.attribution()

    assertEquals("claude", attribution.finalizingAgentId)
    assertEquals(listOf("codex", "claude"), attribution.participatingAgentIds)
  }

  @Test
  fun `attribution falls back to the durable terminal phase record when the ledger terminal entry is pruned`() {
    // The 200-cap ledger may prune the COMPLETE/BLOCKED entry; the durable blocked record carries the finalizer.
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recordBlocked("implement", attemptCount = 3, blockedReason = "exhausted", resolvedAgentId = "claude")
    // Ledger carries only a non-terminal START (the BLOCKED entry was pruned away).
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")

    val attribution = harness.attribution()

    assertEquals("claude", attribution.finalizingAgentId)
    // Participants still include the pruned-ledger record agent via the phase-record sweep.
    assertEquals(listOf("codex", "claude"), attribution.participatingAgentIds)
  }

  @Test
  fun `projection surfaces the ledger-derived finalizing agent even without a goal continuation`() {
    val harness = statusHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(COMPLETE, "commit_push", attemptCount = 1, resolvedAgentId = "claude")

    val projection = requireNotNull(
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
    )

    assertEquals("claude", projection.finalizingAgentId)
  }

  private fun statusHarness(): StatusHarness {
    val repository = StatusInMemoryWorkflowRepository()
    val database = StatusFakeDatabaseSessionFactory(repository)
    val recorder = FeatureTaskRuntimePhaseRecorder(database, StatusNoopSnapshotValidator)
    val decomposeTerminalRecorder = FeatureTaskRuntimeDecomposeTerminalRecorder(database, StatusNoopSnapshotValidator)
    val runInvariantsStore = FeatureTaskRuntimeRunInvariantsStore(database, StatusNoopSnapshotValidator)
    return StatusHarness(
      recorder,
      decomposeTerminalRecorder,
      runInvariantsStore,
      FeatureTaskRuntimeStatusService(recorder, runInvariantsStore, decomposeTerminalRecorder),
    )
  }

  private class StatusHarness(
    val recorder: FeatureTaskRuntimePhaseRecorder,
    val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
    val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
    val service: FeatureTaskRuntimeStatusService,
  ) {
    fun recordRunning(phaseId: String, attemptCount: Int, resolvedAgentId: String = "claude") =
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = WORKFLOW_ID,
          phaseId = phaseId,
          status = "running",
          attemptCount = attemptCount,
          resolvedAgentId = resolvedAgentId,
          finished = false,
          outputArtifact = null,
        ),
      )

    fun recordCompleted(phaseId: String, attemptCount: Int, resolvedAgentId: String = "claude") =
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = WORKFLOW_ID,
          phaseId = phaseId,
          status = "completed",
          attemptCount = attemptCount,
          resolvedAgentId = resolvedAgentId,
          finished = true,
          outputArtifact = """{"contract_version":"0.1"}""",
        ),
      )

    fun recordBlocked(phaseId: String, attemptCount: Int, blockedReason: String, resolvedAgentId: String = "claude") =
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = WORKFLOW_ID,
          phaseId = phaseId,
          status = "blocked",
          attemptCount = attemptCount,
          resolvedAgentId = resolvedAgentId,
          finished = false,
          outputArtifact = null,
          blockedReason = blockedReason,
        ),
      )

    fun recordLedger(
      action: FeatureTaskRuntimePhaseLedgerAction,
      phaseId: String,
      attemptCount: Int,
      resolvedAgentId: String = "claude",
    ) = recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = WORKFLOW_ID,
        action = action,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        blockedReason = if (action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED) "fix loop exhausted" else null,
      ),
    )

    fun recordLoopEdge(
      phaseId: String,
      attemptCount: Int,
      loopId: String,
      edgeIteration: Int,
      resolvedAgentId: String = "claude",
    ) = recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = WORKFLOW_ID,
        action = LOOP_EDGE,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        loopId = loopId,
        edgeIteration = edgeIteration,
      ),
    )

    fun attribution() = agentAttributionFromPhaseState(recorder, WORKFLOW_ID)

    fun recordRunInvariants(featureSize: FeatureTaskRuntimeFeatureSize) {
      runInvariantsStore.resolve(
        workflowId = WORKFLOW_ID,
        proposed =
        FeatureTaskRuntimeRunInvariants(
          specReference = ".feature-specs/SKILL-65/spec.md",
          featureSize = featureSize,
          acceptanceCriteria = listOf("AC-1"),
          mandatesAndOverrides = emptyList(),
        ),
      )
    }
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
    override val telemetryReconciliation: TelemetryReconciliationRepository get() = error("unused")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused")
    override val workflowStates: WorkflowStateRepository = repository
    override val workList = skillbill.ports.persistence.EmptyWorkListRepository
  }
}

private class StatusInMemoryWorkflowRepository : WorkflowStateRepository {
  override fun saveFeatureTaskExecutionIdentity(
    identity: skillbill.ports.persistence.model.FeatureTaskExecutionIdentity,
  ) = Unit

  override fun findStandaloneFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    emptyList<skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate>()

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
