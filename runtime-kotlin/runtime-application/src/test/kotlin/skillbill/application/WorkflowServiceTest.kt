package skillbill.application

import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.model.WorkflowUpdateResult
import skillbill.error.InvalidWorkflowStateSchemaError
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
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-52.1 — covers the typed [WorkflowService] surface end-to-end:
 * each public method returns its declared typed result, including the
 * blocked-continue and unknown-workflow branches, and the workflow
 * engine's loud-fail schema error still propagates through the
 * service.
 */
class WorkflowServiceTest {
  @Test
  fun `open returns Ok with dbPath and snapshot`() {
    val service = newService()
    val result = service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001")
    val ok = assertIs<WorkflowOpenResult.Ok>(result)
    assertEquals("running", ok.snapshot.workflowStatus)
    assertEquals("assess", ok.snapshot.currentStepId)
    assertEquals("/fake/metrics.db", ok.dbPath)
  }

  @Test
  fun `open returns Error for invalid step id`() {
    val service = newService()
    val result = service.open(WorkflowFamilyKind.IMPLEMENT, currentStepId = "not-a-step")
    val error = assertIs<WorkflowOpenResult.Error>(result)
    assertTrue(error.error.contains("Invalid current_step_id"))
  }

  @Test
  fun `update validation error does not carry a dbPath wire field`() {
    val service = newService()
    // Validation-time error: invalid workflow_status string is rejected
    // before the unit of work runs, so the typed error carries no dbPath.
    val result = service.update(
      WorkflowFamilyKind.IMPLEMENT,
      WorkflowUpdateRequest(
        workflowId = "irrelevant",
        workflowStatus = "not-a-status",
        currentStepId = "assess",
        sessionId = "",
      ),
    )
    val error = assertIs<WorkflowUpdateResult.Error>(result)
    assertTrue(error.error.contains("Invalid workflow_status"))
    assertNull(
      error.dbPath,
      "Validation-time WorkflowUpdateResult.Error must not carry a dbPath; " +
        "the wire envelope predates the typed result and omits db_path on this path.",
    )
  }

  @Test
  fun `update unknown-workflow error does not carry a dbPath wire field`() {
    val service = newService()
    // Unknown-workflow error inside the transaction: the legacy wire
    // envelope intentionally omitted `db_path` on this branch, so the
    // typed result must keep dbPath null even though the transaction ran.
    val result = service.update(
      WorkflowFamilyKind.IMPLEMENT,
      WorkflowUpdateRequest(
        workflowId = "missing",
        workflowStatus = "running",
        currentStepId = "assess",
        sessionId = "",
      ),
    )
    val error = assertIs<WorkflowUpdateResult.Error>(result)
    assertEquals("Unknown workflow_id 'missing'.", error.error)
    assertNull(
      error.dbPath,
      "Unknown-workflow WorkflowUpdateResult.Error must not carry a dbPath; " +
        "the legacy wire envelope omitted db_path on this path.",
    )
  }

  @Test
  fun `list returns the opened workflow ids in expected order`() {
    val service = newService()
    val first = assertIs<WorkflowOpenResult.Ok>(
      service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"),
    )
    val second = assertIs<WorkflowOpenResult.Ok>(
      service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-002"),
    )
    val result = service.list(WorkflowFamilyKind.IMPLEMENT)
    assertEquals(2, result.workflowCount)
    assertEquals(2, result.workflows.size)
    assertEquals(
      setOf(first.workflowId, second.workflowId),
      result.workflows.map { it.workflowId }.toSet(),
    )
  }

  @Test
  fun `get returns Ok for known workflow`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))
    val got = service.get(WorkflowFamilyKind.IMPLEMENT, opened.workflowId)
    val ok = assertIs<WorkflowGetResult.Ok>(got)
    assertEquals(opened.workflowId, ok.workflowId)
  }

  @Test
  fun `continueWorkflow with missing artifacts returns Standard with continue_status blocked`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))
    service.update(
      WorkflowFamilyKind.IMPLEMENT,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "blocked",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf("preplan_digest" to mapOf("ok" to true)),
      ),
    )
    val continued = service.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, opened.workflowId)
    val standard = assertIs<WorkflowContinueResult.Standard>(continued)
    assertEquals("blocked", standard.view.continueStatus)
    assertEquals(listOf("plan"), standard.view.resume.missingArtifacts)
  }

  @Test
  fun `InvalidWorkflowStateSchemaError loud-fails through WorkflowService get`() {
    val workflows = InMemoryWorkflowStates()
    // Persist a malformed durable record directly through the repository
    // so the engine reads it via the typed service surface.
    val definition = FeatureImplementWorkflowDefinition.definition
    // unterminated JSON triggers the loud-fail at the engine read seam.
    val malformed = WorkflowEngine.openRecord(definition, "wfl-loud", "fis-001", "assess").copy(
      stepsJson = """[{"step_id":"assess"}""",
    ).toRecord()
    workflows.saveFeatureImplementWorkflow(malformed)
    val service = WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      service.get(WorkflowFamilyKind.IMPLEMENT, "wfl-loud")
    }
  }

  @Test
  fun `decomposed parent lookup ignores child workflows that only carry runtime projection`() {
    val workflows = InMemoryWorkflowStates()
    val childRuntime = decompositionRuntime(status = "blocked")
    val parentRuntime = decompositionRuntime(status = "in_progress")
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch = mapOf(DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(childRuntime)),
      ),
    )
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(parentRuntime),
        ),
      ),
    )

    val selected = workflows.findDecomposedParentWorkflow("SKILL-52.1")

    assertEquals("wfl-parent", selected?.workflowId)
  }

  private fun newService(): WorkflowService {
    val workflows = InMemoryWorkflowStates()
    return WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
    )
  }
}

private fun workflowRecord(workflowId: String, artifactsPatch: Map<String, Any?>): WorkflowStateRecord {
  val definition = FeatureImplementWorkflowDefinition.definition
  val opened = WorkflowEngine.openRecord(definition, workflowId, "fis-001", "assess")
  return WorkflowEngine.updateRecord(
    definition,
    opened,
    skillbill.workflow.model.WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "plan",
      stepUpdates = null,
      artifactsPatch = artifactsPatch,
      sessionId = "fis-001",
    ),
  ).toRecord()
}

private fun decompositionRuntime(status: String): DecompositionManifest = DecompositionManifest(
  issueKey = "SKILL-52.1",
  featureName = "install-policy-extraction",
  parentSpecPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_3_install-policy.md",
  status = status,
  executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
  baseBranch = "main",
  featureBranch = "feat/SKILL-52.1-hexagonal-runtime-hardening",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
  subtasks = listOf(
    DecompositionSubtask(
      id = 1,
      name = "install-policy-foundation",
      specPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy/spec_subtask_1.md",
      status = status,
      workflowId = "wfl-child",
    ),
  ),
)

private class FakeDatabaseSessionFactory(
  private val workflowStates: WorkflowStateRepository,
  private val fakeDbPath: Path = Path.of("/fake/metrics.db"),
) : DatabaseSessionFactory {
  override fun resolveDbPath(dbOverride: String?): Path = fakeDbPath
  override fun databaseExists(dbOverride: String?): Boolean = true
  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unit())
  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unit())

  private fun unit(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = fakeDbPath
    override val workflowStates: WorkflowStateRepository = this@FakeDatabaseSessionFactory.workflowStates
    override val learnings: LearningRepository
      get() = error("LearningRepository is not exercised in WorkflowServiceTest.")
    override val reviews: ReviewRepository
      get() = error("ReviewRepository is not exercised in WorkflowServiceTest.")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("LifecycleTelemetryRepository is not exercised in WorkflowServiceTest.")
    override val telemetryOutbox: TelemetryOutboxRepository
      get() = error("TelemetryOutboxRepository is not exercised in WorkflowServiceTest.")
  }
}

private class InMemoryWorkflowStates : WorkflowStateRepository {
  private val implement = mutableMapOf<String, WorkflowStateRecord>()
  private val verify = mutableMapOf<String, WorkflowStateRecord>()

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    implement[row.workflowId] = row
  }
  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    verify[row.workflowId] = row
  }
  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = implement[workflowId]
  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = verify[workflowId]
  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    implement.values.toList().take(limit)
  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = verify.values.toList().take(limit)
  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = implement.values.lastOrNull()
  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = verify.values.lastOrNull()
  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null
  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null
}
