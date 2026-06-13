package skillbill.application

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.encodeDecompositionManifestYaml
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.model.WorkflowUpdateResult
import skillbill.application.workflow.ContinuationStepResult
import skillbill.application.workflow.DecompositionWorkflowContinuation
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.alignSubtaskResumeStep
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.findDecomposedParentWorkflow
import skillbill.application.workflow.repoRoot
import skillbill.application.workflow.toRecord
import skillbill.application.workflow.toSnapshot
import skillbill.error.InvalidGoalObservabilityEventSchemaError
import skillbill.error.InvalidGoalProgressEventSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.goalrunner.model.GoalSessionAccounting
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.GoalProgressEventValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    assertEquals(listOf("plan"), standard.view.compact.missingArtifactKeys)
    val missingSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }
    assertFalse(missingSummary.present)
    assertEquals("missing_required_artifact", missingSummary.omissionReason)
    assertEquals(
      "Use workflow show for read-only full-state inspection, including the complete durable artifacts map.",
      standard.view.compact.readOnlyFullStateGuidance,
    )
  }

  @Test
  fun `InvalidWorkflowStateSchemaError loud-fails through WorkflowService get and continue before projection`() {
    // SKILL-52.3 subtask 1: the concrete workflow-state schema validator now
    // lives in `runtime-infra-fs` and is reached through the injected
    // `WorkflowSnapshotValidator` port. This test pins the seam contract: the
    // engine read path invokes the injected validator and surfaces its typed
    // `InvalidWorkflowStateSchemaError` through the service. Real-schema
    // coverage (which inputs trigger the error) lives in the infra-fs
    // `WorkflowStateSchemaViolationsTest`.
    val workflows = InMemoryWorkflowStates()
    val record = testWorkflowEngine.openRecord(
      FeatureImplementWorkflowDefinition.definition,
      "wfl-loud",
      "fis-001",
      "assess",
    ).toRecord()
    workflows.saveFeatureImplementWorkflow(record)
    val loudFailValidator = object : WorkflowSnapshotValidator {
      override fun validate(snapshot: Map<String, Any?>, slug: String): Unit =
        throw InvalidWorkflowStateSchemaError("Workflow '$slug': snapshot fails schema validation at '<root>'.")
    }
    val service = WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = loudFailValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      service.get(WorkflowFamilyKind.IMPLEMENT, "wfl-loud")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      service.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, "wfl-loud")
    }
  }

  @Test
  fun `InvalidWorkflowStateSchemaError loud-fails through WorkflowService update before acknowledgement`() {
    val workflows = InMemoryWorkflowStates()
    val opened = testWorkflowEngine.openRecord(
      FeatureImplementWorkflowDefinition.definition,
      "wfl-update-loud",
      "fis-001",
      "assess",
    ).toRecord()
    workflows.saveFeatureImplementWorkflow(opened)
    val loudFailValidator = object : WorkflowSnapshotValidator {
      override fun validate(snapshot: Map<String, Any?>, slug: String): Unit =
        throw InvalidWorkflowStateSchemaError("Workflow '$slug': snapshot fails schema validation at '<root>'.")
    }
    val service = WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = loudFailValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      service.update(
        WorkflowFamilyKind.IMPLEMENT,
        WorkflowUpdateRequest(
          workflowId = "wfl-update-loud",
          workflowStatus = "running",
          currentStepId = "preplan",
          stepUpdates = listOf(mapOf("step_id" to "preplan", "status" to "running", "attempt_count" to 1)),
          artifactsPatch = mapOf("assessment" to mapOf("ok" to true), "branch" to mapOf("ok" to true)),
          sessionId = "",
        ),
      )
    }
  }

  @Test
  fun `progress event update persists goal observability latest and bounded history artifacts`() {
    val workflows = InMemoryWorkflowStates()
    val service = WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
      goalObservabilityEventValidator = testGoalObservabilityEventValidator,
    )
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))

    val updated = service.update(
      WorkflowFamilyKind.IMPLEMENT,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-61",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
          "progress_event" to mapOf(
            "step_id" to "implement",
            "attempt_count" to 1,
            "source" to "phase_subagent",
            "kind" to "durable_progress",
            "message" to "editing runtime files",
            "sequence" to 7,
            "timestamp" to "2026-06-01T00:00:00Z",
          ),
        ),
        sessionId = "fis-001",
      ),
    )

    val ok = assertIs<WorkflowUpdateResult.Ok>(updated)
    assertProgressEventAcknowledgement(ok)
    val persisted = assertIs<WorkflowGetResult.Ok>(
      service.get(WorkflowFamilyKind.IMPLEMENT, opened.workflowId),
    )
    assertPersistedProgressEventArtifacts(persisted, opened.workflowId)
  }

  @Test
  fun `decomposed parent lookup ignores child workflows that only carry runtime projection`() {
    val workflows = InMemoryWorkflowStates()
    val childRuntime = decompositionRuntime(status = "blocked")
    val parentRuntime = decompositionRuntime(status = "in_progress")
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch = mapOf(
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(childRuntime, testDecompositionManifestValidator),
        ),
      ),
    )
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(parentRuntime, testDecompositionManifestValidator),
        ),
      ),
    )

    val selected = workflows.findDecomposedParentWorkflow("SKILL-52.1", testDecompositionManifestValidator)

    assertEquals("wfl-parent", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup ignores goal-continuation child workflows even when child plan is decompose`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          "goal_continuation" to mapOf(
            "enabled" to true,
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(
              decompositionRuntime(status = "in_progress"),
              testDecompositionManifestValidator,
            ),
        ),
      ),
    )
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(
              decompositionRuntime(status = "in_progress"),
              testDecompositionManifestValidator,
            ),
        ),
      ),
    )

    val selected = workflows.findDecomposedParentWorkflow("SKILL-52.1", testDecompositionManifestValidator)

    assertEquals("wfl-parent", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup prefers active runtime over completed lineage for same issue key`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-completed-discovery",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(
              decompositionRuntime(status = "complete"),
              testDecompositionManifestValidator,
            ),
        ),
      ),
    )
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-active-implementation",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(
              decompositionRuntime(status = "blocked"),
              testDecompositionManifestValidator,
            ),
        ),
      ),
    )

    val selected = workflows.findDecomposedParentWorkflow("SKILL-52.1", testDecompositionManifestValidator)

    assertEquals("wfl-active-implementation", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup rejects multiple active runtimes for same issue key`() {
    val workflows = InMemoryWorkflowStates()
    listOf("wfl-active-a", "wfl-active-b").forEach { workflowId ->
      workflows.saveFeatureImplementWorkflow(
        workflowRecord(
          workflowId = workflowId,
          artifactsPatch = mapOf(
            "plan" to mapOf("mode" to "decompose"),
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
              encodeDecompositionManifestMap(
                decompositionRuntime(status = "blocked"),
                testDecompositionManifestValidator,
              ),
          ),
        ),
      )
    }

    val error = assertFailsWith<IllegalStateException> {
      workflows.findDecomposedParentWorkflow("SKILL-52.1", testDecompositionManifestValidator)
    }

    assertEquals(
      "Ambiguous decomposed parent workflows for 'SKILL-52.1': wfl-active-a, wfl-active-b. " +
        "Pass an explicit workflow or manifest selector before continuing.",
      error.message,
    )
  }

  @Test
  fun `goal manifest store imports active checked-in projection over completed lineage`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-import")
    val completedPath = repoRoot.resolve(".feature-specs/SKILL-52.1-a-discovery/decomposition-manifest.yaml")
    val activePath = repoRoot.resolve(".feature-specs/SKILL-52.1-z-implementation/decomposition-manifest.yaml")
    val unrelatedInvalidPath = repoRoot.resolve(".feature-specs/SKILL-55-launch-readiness/decomposition-manifest.yaml")
    Files.createDirectories(completedPath.parent)
    Files.createDirectories(activePath.parent)
    Files.createDirectories(unrelatedInvalidPath.parent)
    Files.writeString(
      completedPath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "complete"),
        testDecompositionManifestValidator,
        TestDecompositionManifestFileStore,
      ),
    )
    Files.writeString(
      activePath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "blocked"),
        testDecompositionManifestValidator,
        TestDecompositionManifestFileStore,
      ),
    )
    Files.writeString(
      unrelatedInvalidPath,
      """
      ---
      contract_version: "0.3"
      issue_key: "SKILL-55"
      current_subtask_intent:
        subtask_id: 1
        action: "complete"
      ---
      """.trimIndent(),
    )
    val store = WorkflowGoalRunnerManifestStore(
      database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
      decompositionManifestFileStore = TestDecompositionManifestFileStore,
    )

    val state = store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot)

    assertEquals("blocked", state?.manifest?.status)
    assertEquals("wfl-child", state?.manifest?.subtasks?.single()?.workflowId)
  }

  @Test
  fun `goal manifest store refreshes stale db projection from complete checked-in projection`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-refresh-complete")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52.1-implementation/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        completeDecompositionRuntime(),
        testDecompositionManifestValidator,
        TestDecompositionManifestFileStore,
      ),
    )
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(
              decompositionRuntime(status = "blocked"),
              testDecompositionManifestValidator,
            ),
        ),
      ),
    )
    val store = WorkflowGoalRunnerManifestStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
      decompositionManifestFileStore = TestDecompositionManifestFileStore,
    )

    val refreshed = store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot)
    val persisted = store.loadByIssueKey("SKILL-52.1", repoRoot = null)

    assertEquals("complete", refreshed?.manifest?.status)
    assertEquals(0, refreshed?.manifest?.currentSubtaskIntent?.subtaskId)
    assertEquals("none", refreshed?.manifest?.currentSubtaskIntent?.action)
    assertEquals("complete", refreshed?.manifest?.subtasks?.single()?.status)
    assertEquals("sha-complete", refreshed?.manifest?.subtasks?.single()?.commitSha)
    assertEquals("complete", persisted?.manifest?.status)
    assertEquals(0, persisted?.manifest?.currentSubtaskIntent?.subtaskId)
  }

  @Test
  fun `goal manifest store rejects multiple active checked-in projections`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-ambiguous")
    val firstPath = repoRoot.resolve(".feature-specs/SKILL-52.1-a-implementation/decomposition-manifest.yaml")
    val secondPath = repoRoot.resolve(".feature-specs/SKILL-52.1-b-implementation/decomposition-manifest.yaml")
    Files.createDirectories(firstPath.parent)
    Files.createDirectories(secondPath.parent)
    listOf(firstPath, secondPath).forEach { path ->
      Files.writeString(
        path,
        encodeDecompositionManifestYaml(
          decompositionRuntime(status = "blocked"),
          testDecompositionManifestValidator,
          TestDecompositionManifestFileStore,
        ),
      )
    }
    val store = WorkflowGoalRunnerManifestStore(
      database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
      decompositionManifestFileStore = TestDecompositionManifestFileStore,
    )

    val error = assertFailsWith<IllegalStateException> {
      store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot)
    }

    assertEquals(
      "Ambiguous checked-in decomposition manifests for 'SKILL-52.1': " +
        ".feature-specs/SKILL-52.1-a-implementation/decomposition-manifest.yaml, " +
        ".feature-specs/SKILL-52.1-b-implementation/decomposition-manifest.yaml. " +
        "Pass an explicit workflow or manifest selector before continuing.",
      error.message,
    )
  }

  private fun newService(): WorkflowService {
    val workflows = InMemoryWorkflowStates()
    return WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )
  }
}

class WorkflowGoalStatusProjectionTest {
  @Test
  fun `goal status omits stale active observability after terminal projection wins`() {
    val workflows = InMemoryWorkflowStates()
    saveCompleteGoalParent(workflows)
    saveStaleRunningChildWithObservability(workflows)
    saveAuthoritativeCompleteChild(workflows)

    val projection = newGoalStatusService(workflows).status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-52.1",
        invokedAgentId = "codex",
        repoRoot = Path.of("").toAbsolutePath(),
      ),
    )

    requireNotNull(projection)
    assertEquals(1, projection.completeCount)
    assertEquals(0, projection.pendingCount)
    assertEquals(0, projection.blockedCount)
    assertNull(projection.currentSubtaskId)
    assertNull(projection.latestObservabilityEvent)
    assertNull(projection.latestLivenessSignal)
  }

  private fun saveCompleteGoalParent(workflows: InMemoryWorkflowStates) {
    val manifest = decompositionRuntime(status = "complete").copy(
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "none"),
      subtasks = listOf(
        DecompositionSubtask(
          id = 1,
          name = "install-policy-foundation",
          specPath = ".feature-specs/SKILL-52.1/spec_subtask_1.md",
          status = "complete",
          workflowId = "wfl-authoritative",
          commitSha = "sha-1",
          lastResumableStep = "commit_push",
        ),
      ),
    )
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            encodeDecompositionManifestMap(manifest, testDecompositionManifestValidator),
        ),
      ),
    )
  }

  private fun saveStaleRunningChildWithObservability(workflows: InMemoryWorkflowStates) {
    val opened = testWorkflowEngine.openRecord(
      FeatureImplementWorkflowDefinition.definition,
      "wfl-stale",
      "fis-stale",
      "preplan",
    )
    val running = testWorkflowEngine.updateRecord(
      FeatureImplementWorkflowDefinition.definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1)),
        artifactsPatch = goalContinuationArtifact() + staleObservabilityArtifact(),
        sessionId = "fis-stale",
      ),
    )
    workflows.saveFeatureImplementWorkflow(running.toRecord())
  }

  private fun saveAuthoritativeCompleteChild(workflows: InMemoryWorkflowStates) {
    val opened = testWorkflowEngine.openRecord(
      FeatureImplementWorkflowDefinition.definition,
      "wfl-authoritative",
      "fis-done",
      "preplan",
    )
    val complete = testWorkflowEngine.updateRecord(
      FeatureImplementWorkflowDefinition.definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "commit_push",
        stepUpdates = listOf(mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1)),
        artifactsPatch = goalContinuationArtifact() + completeOutcomeArtifact(),
        sessionId = "fis-done",
      ),
    )
    workflows.saveFeatureImplementWorkflow(complete.toRecord())
  }

  private fun goalContinuationArtifact(): Map<String, Any?> = mapOf(
    "goal_continuation" to mapOf(
      "issue_key" to "SKILL-52.1",
      "subtask_id" to 1,
      "suppress_pr" to true,
    ),
  )

  private fun staleObservabilityArtifact(): Map<String, Any?> = mapOf(
    "goal_observability_latest_event" to mapOf(
      "contract_version" to "0.1",
      "issue_key" to "SKILL-52.1",
      "subtask_id" to 1,
      "workflow_id" to "wfl-stale",
      "workflow_phase" to "implement",
      "worker_role" to "phase_subagent",
      "liveness_class" to "durable_progress",
      "activity_summary" to "stale edit",
      "timestamp" to "2026-06-01T00:00:00Z",
      "sequence_number" to 10,
    ),
  )

  private fun completeOutcomeArtifact(): Map<String, Any?> = mapOf(
    "goal_continuation_outcome" to mapOf(
      "issue_key" to "SKILL-52.1",
      "subtask_id" to 1,
      "status" to "complete",
      "workflow_id" to "wfl-authoritative",
      "commit_sha" to "sha-1",
      "last_resumable_step" to "commit_push",
    ),
  )

  private fun newGoalStatusService(workflows: InMemoryWorkflowStates): GoalRunnerStatusService {
    val database = FakeDatabaseSessionFactory(workflows)
    return GoalRunnerStatusService(
      manifestStore = WorkflowGoalRunnerManifestStore(
        database = database,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestFileStore = TestDecompositionManifestFileStore,
      ),
      outcomeStore = WorkflowGoalRunnerOutcomeStore(
        database = database,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        goalObservabilityEventValidator = testGoalObservabilityEventValidator,
      ),
    )
  }
}

private object HeadShaGitOperations : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "measured-head-sha")
}

/** Kept separate from [WorkflowGoalRunnerOutcomeStoreTest] to stay under the detekt LargeClass threshold. */
class GoalRunnerCommitShaRecoveryTest {
  @Test
  fun `goal runner outcome store backfills missing commit sha from measured git head`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      gitOperations = HeadShaGitOperations,
    )

    val outcome = store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, outcome.status)
    assertEquals("measured-head-sha", outcome.commitSha)
  }

  @Test
  fun `goal runner outcome store durably persists the measured completion`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      gitOperations = HeadShaGitOperations,
    )

    store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))
    val durable = store.terminalOutcome("wfl-child", "SKILL-52.1", 1)

    requireNotNull(durable)
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, durable.status)
    assertEquals("measured-head-sha", durable.commitSha)
  }

  @Test
  fun `goal runner outcome store stays blocked when measured git head is unavailable`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      gitOperations = NoopWorkflowGitOperations,
    )

    val outcome = store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME, outcome.status)
  }

  @Test
  fun `goal runner outcome store does not measure git head without a repo root`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      gitOperations = HeadShaGitOperations,
    )

    val outcome = store.terminalOutcome("wfl-child", "SKILL-52.1", 1)

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME, outcome.status)
  }

  @Test
  fun `goal runner outcome store persists recovered missing result prefix terminal envelope`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val outcome = store.recoverMissingResultPrefixOutput(
      workflowId = "wfl-child",
      issueKey = "SKILL-52.1",
      subtaskId = 1,
      output = mapOf(
        "status" to "blocked",
        "workflow_id" to "wfl-child",
        "last_resumable_step" to "implement",
        "blocked_reason" to "prefixless terminal json",
      ),
    )

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("implement", outcome.lastResumableStep)
    val saved = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot()
    val artifacts = decodeWorkflowArtifactsForTest(saved.artifactsJson)
    assertTrue(artifacts.containsKey("goal_runner_missing_result_prefix_recovery"))
    assertEquals("prefixless terminal json", outcome.blockedReason)
  }

  @Test
  fun `reconciliation backfills a pre-existing complete-without-sha outcome from measured git head`() {
    // SKILL-68 AC6 case iv: a previously persisted complete-without-SHA store row is healed on the
    // next reconciliation (manifest-workflowId-independent) — the measured HEAD SHA is durably
    // backfilled and the subtask resolves COMPLETE.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(completeWithoutShaOutcome("wfl-child"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      gitOperations = HeadShaGitOperations,
    )

    val reconciled = store.reconcileAuthoritativeOutcomes(issueKey = "SKILL-52.1", repoRoot = Path.of("."))

    val outcome = requireNotNull(reconciled[1])
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, outcome.status)
    assertEquals("measured-head-sha", outcome.commitSha)
    val durable = requireNotNull(store.terminalOutcome("wfl-child", "SKILL-52.1", 1))
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, durable.status)
    assertEquals("measured-head-sha", durable.commitSha)
  }

  @Test
  fun `reconciliation without a repo root leaves a complete-without-sha outcome unmeasured`() {
    // SKILL-68 AC5 control: a pure read caller (repoRoot=null) never measures git HEAD, so a
    // complete-without-SHA outcome is not silently healed by a status read.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(completeWithoutShaOutcome("wfl-child"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      gitOperations = HeadShaGitOperations,
    )

    store.reconcileAuthoritativeOutcomes(issueKey = "SKILL-52.1")
    val durable = requireNotNull(store.terminalOutcome("wfl-child", "SKILL-52.1", 1))

    assertNull(durable.commitSha, "a read-only reconciliation must not backfill a measured SHA")
  }

  private fun commitPushCompletedWithoutCommitSha(workflowId: String): WorkflowStateRecord {
    val opened = testWorkflowEngine.openRecord(
      FeatureImplementWorkflowDefinition.definition,
      workflowId,
      "fis-no-sha",
      "preplan",
    )
    val completed = testWorkflowEngine.updateRecord(
      FeatureImplementWorkflowDefinition.definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "commit_push",
        stepUpdates = listOf(mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "fis-no-sha",
      ),
    )
    return completed.toRecord()
  }

  // A child stranded in the legacy bug state: commit_push completed under suppress_pr and a durable
  // goal_continuation_outcome already records `complete`, but the SHA was dropped.
  private fun completeWithoutShaOutcome(workflowId: String): WorkflowStateRecord {
    val opened = testWorkflowEngine.openRecord(
      FeatureImplementWorkflowDefinition.definition,
      workflowId,
      "fis-stale-complete",
      "preplan",
    )
    val completed = testWorkflowEngine.updateRecord(
      FeatureImplementWorkflowDefinition.definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "commit_push",
        stepUpdates = listOf(mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
          "goal_continuation_outcome" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "status" to "complete",
            "workflow_id" to workflowId,
            "last_resumable_step" to "commit_push",
          ),
        ),
        sessionId = "fis-stale-complete",
      ),
    )
    return completed.toRecord()
  }
}

/**
 * SKILL-64 Subtask 4 (AC1, AC2): the compact workflow-update acknowledgement
 * byte-budget regression. Kept in its own class so it does not push the broad
 * [WorkflowServiceTest] over the detekt LargeClass threshold.
 */
class WorkflowUpdateAcknowledgementBudgetTest {
  @Test
  fun `compact update acknowledgement stays under byte ceiling and omits full durable state`() {
    // A workflow update with a representative LARGE artifacts_patch must return a
    // compact typed acknowledgement, not a full snapshot. The serialized ack
    // stays under a named ceiling and carries only the documented compact fields
    // + read-only full-state guidance — never the full durable artifacts map or
    // the full per-step list.
    val service = newAckBudgetService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.open(WorkflowFamilyKind.IMPLEMENT, sessionId = "fis-001"))
    val updated = service.update(
      WorkflowFamilyKind.IMPLEMENT,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1)),
        artifactsPatch = mapOf(
          "plan" to mapOf("mode" to "implement", "body" to "x".repeat(12000)),
          "preplan_digest" to mapOf("risk" to "low", "notes" to "y".repeat(8000)),
        ),
        sessionId = "fis-001",
      ),
    )

    val ok = assertIs<WorkflowUpdateResult.Ok>(updated)
    val ack = ok.acknowledgement
    assertEquals("ok", ack.status)
    assertEquals(opened.workflowId, ack.workflowId)
    assertEquals("running", ack.workflowStatus)
    assertEquals("implement", ack.currentStepId)
    assertEquals(listOf("implement"), ack.updatedStepIds)
    assertEquals(listOf("plan", "preplan_digest"), ack.updatedArtifactKeys)

    // Build the compact ack wire shape (typed ack map + result-level db_path) and
    // assert it stays under the ceiling. A regression that echoes the full
    // durable artifacts map back in the ack would blow past it.
    val ackMap = WorkflowEngine.updateAcknowledgementMap(ack) + mapOf("db_path" to ok.dbPath)
    val serialized = skillbill.contracts.JsonSupport.mapToJsonString(ackMap)
    val byteSize = serialized.toByteArray(Charsets.UTF_8).size
    assertTrue(
      byteSize < COMPACT_UPDATE_ACK_PAYLOAD_BYTE_CEILING,
      "Compact update acknowledgement was $byteSize bytes, exceeding the " +
        "$COMPACT_UPDATE_ACK_PAYLOAD_BYTE_CEILING ceiling; the full durable state was likely echoed back.",
    )
    // The ack carries only documented compact fields + read-only guidance.
    assertEquals(
      setOf(
        "status",
        "workflow_id",
        "workflow_name",
        "workflow_status",
        "current_step_id",
        "updated_step_ids",
        "updated_artifact_keys",
        "read_only_full_state_guidance",
        "db_path",
      ),
      ackMap.keys,
    )
    // The full durable artifacts map and the full per-step list must NOT appear.
    assertFalse(serialized.contains("\"artifacts\""))
    assertFalse(serialized.contains("\"steps\""))
    assertFalse(serialized.contains("x".repeat(2000)))
    assertFalse(serialized.contains("y".repeat(2000)))
    assertTrue(ack.readOnlyFullStateGuidance.isNotBlank())
  }

  private fun newAckBudgetService(): WorkflowService = WorkflowService(
    database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
    decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
    workflowSnapshotValidator = testWorkflowSnapshotValidator,
    decompositionManifestValidator = testDecompositionManifestValidator,
  )
}

class WorkflowGoalRunnerOutcomeStoreTest {
  @Test
  fun `goal runner outcome store reads durable blocked continuation outcome`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
          "goal_continuation_outcome" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "status" to "blocked",
            "workflow_id" to "wfl-child",
            "blocked_reason" to "preplan could not progress",
            "last_resumable_step" to "preplan",
          ),
        ),
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val outcome = store.terminalOutcome("wfl-child", "SKILL-52.1", 1)

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("preplan could not progress", outcome.blockedReason)
    assertEquals("preplan", outcome.lastResumableStep)
  }

  @Test
  @Suppress("LongMethod")
  fun `goal runner outcome reconciliation closes stale running child in favor of authoritative terminal workflow`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureImplementWorkflowDefinition.definition
    val staleOpened = testWorkflowEngine.openRecord(definition, "wfl-stale", "fis-001", "preplan")
    val staleRunning = testWorkflowEngine.updateRecord(
      definition,
      staleOpened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "fis-001",
      ),
    )
    val authoritativeOpened = testWorkflowEngine.openRecord(definition, "wfl-authoritative", "fis-002", "preplan")
    val authoritative = testWorkflowEngine.updateRecord(
      definition,
      authoritativeOpened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "commit_push",
        stepUpdates = listOf(
          mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
          "goal_continuation_outcome" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "status" to "complete",
            "workflow_id" to "wfl-authoritative",
            "commit_sha" to "sha-1",
            "last_resumable_step" to "commit_push",
          ),
        ),
        sessionId = "fis-002",
      ),
    )
    workflows.saveFeatureImplementWorkflow(staleRunning.toRecord())
    workflows.saveFeatureImplementWorkflow(authoritative.toRecord())
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", setOf("wfl-stale", "wfl-authoritative"))

    val subtaskOutcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, subtaskOutcome.status)
    assertEquals("wfl-authoritative", subtaskOutcome.workflowId)
    val stale = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-stale")).toSnapshot()
    assertEquals("blocked", stale.workflowStatus)
    assertEquals("blocked", decodeWorkflowStepsForTest(stale.stepsJson).getValue("implement"))
    assertContains(stale.artifactsJson, "stale running child 'wfl-stale'")
  }

  @Test
  fun `goal runner outcome reconciliation closes inactive running child without authoritative sibling`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureImplementWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-orphan", "fis-001", "preplan")
    val running = testWorkflowEngine.updateRecord(
      definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "fis-001",
      ),
    )
    workflows.saveFeatureImplementWorkflow(running.toRecord())
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", emptySet())

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wfl-orphan", outcome.workflowId)
    val orphan = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-orphan")).toSnapshot()
    assertEquals("blocked", orphan.workflowStatus)
    assertContains(orphan.artifactsJson, "no longer active")
  }

  @Test
  fun `goal runner outcome reconciliation keeps active running child without authoritative terminal outcome`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureImplementWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-active", "fis-001", "preplan")
    val running = testWorkflowEngine.updateRecord(
      definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "fis-001",
      ),
    )
    workflows.saveFeatureImplementWorkflow(running.toRecord())
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", setOf("wfl-active"))

    assertTrue(outcomes.isEmpty())
    val active = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-active")).toSnapshot()
    assertEquals("running", active.workflowStatus)
    assertEquals("running", decodeWorkflowStepsForTest(active.stepsJson).getValue("implement"))
  }

  @Test
  @Suppress("LongMethod")
  fun `goal runner outcome reconciliation keeps active retry when only blocked sibling exists`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureImplementWorkflowDefinition.definition
    val blockedOpened = testWorkflowEngine.openRecord(definition, "wfl-blocked", "fis-001", "preplan")
    val blocked = testWorkflowEngine.updateRecord(
      definition,
      blockedOpened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "blocked",
        currentStepId = "review",
        stepUpdates = listOf(
          mapOf("step_id" to "review", "status" to "blocked", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "fis-001",
      ),
    )
    val activeOpened = testWorkflowEngine.openRecord(definition, "wfl-active", "fis-002", "preplan")
    val active = testWorkflowEngine.updateRecord(
      definition,
      activeOpened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "fis-002",
      ),
    )
    workflows.saveFeatureImplementWorkflow(blocked.toRecord())
    workflows.saveFeatureImplementWorkflow(active.toRecord())
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", setOf("wfl-active"))

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wfl-blocked", outcome.workflowId)
    val stillActive = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-active")).toSnapshot()
    assertEquals("running", stillActive.workflowStatus)
    assertEquals("running", decodeWorkflowStepsForTest(stillActive.stepsJson).getValue("implement"))
    val stillBlocked = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-blocked")).toSnapshot()
    assertEquals("blocked", stillBlocked.workflowStatus)
  }

  @Test
  fun `goal runner outcome store blocks active running step instead of stale requested step`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureImplementWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-child", "fis-001", "preplan")
    val running = testWorkflowEngine.updateRecord(
      definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-52.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "fis-001",
      ),
    )
    workflows.saveFeatureImplementWorkflow(running.toRecord())
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val blockedStep = store.markBlocked("wfl-child", "timeout", "preplan")

    assertEquals("implement", blockedStep)
    val saved = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot()
    assertEquals("blocked", saved.workflowStatus)
    assertEquals("implement", saved.currentStepId)
    val steps = decodeWorkflowStepsForTest(saved.stepsJson)
    assertEquals("completed", steps.getValue("preplan"))
    assertEquals("blocked", steps.getValue("implement"))
  }

  @Test
  fun `goal runner progress reports finish after terminal step completes despite stale current step`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureImplementWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-child", "fis-001", "preplan")
    val finished = testWorkflowEngine.updateRecord(
      definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "audit",
        stepUpdates = listOf(
          mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1),
        ),
        artifactsPatch = null,
        sessionId = "fis-001",
      ),
    )
    workflows.saveFeatureImplementWorkflow(finished.toRecord())
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val progress = store.progress("wfl-child")

    requireNotNull(progress)
    assertEquals("finish", progress.currentStepId)
    assertEquals("workflow_status=running; step=finish", progress.latestLivenessSignal)
  }

  @Test
  fun `goal runner progress keeps declared liveness when goal observability latest event is malformed`() {
    // SKILL-64 Subtask 3 (F-R01): a corrupt goal_observability_latest_event must
    // NOT return null for the whole poll (callers wrap progress() in
    // runCatching{}.getOrNull()) — that would permanently disable deterministic
    // declared-progress liveness and revert to legacy false-kill heuristics.
    // The declared-progress read is independent of the observability decode.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch = mapOf(
          "goal_observability_latest_event" to mapOf(
            "contract_version" to "0.1",
            "issue_key" to "SKILL-61",
          ),
          GoalProgressEvent(
            eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
            workflowId = "wfl-child",
            workflowPhase = "validate",
            processAlive = true,
            sequenceNumber = 5,
            timestamp = "2026-06-02T10:00:00Z",
            operationName = "gradlew check",
            operationKind = "build",
            expectedLong = true,
          ).let { event -> "goal_progress_latest_event" to event.toArtifactMap() },
        ),
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      goalObservabilityEventValidator = object : GoalObservabilityEventValidator {
        override fun validate(event: Map<String, Any?>, sourceLabel: String) {
          throw InvalidGoalObservabilityEventSchemaError(sourceLabel, "subtask_id", "subtask_id is required.")
        }
      },
    )

    val progress = requireNotNull(store.progress("wfl-child"))

    assertNull(progress.latestGoalObservabilityEvent)
    val declared = requireNotNull(progress.latestDeclaredProgressEvent)
    assertEquals(GoalProgressEventKind.OPERATION_HEARTBEAT, declared.eventKind)
    assertEquals("gradlew check", declared.operationName)
    assertTrue(declared.processAlive)
  }

  @Test
  fun `goal runner outcome store appends worker subtask request outcomes`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(workflowRecord("wfl-child", emptyMap()))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )
    val acceptedRequest = GoalRunnerWorkerSubtaskRequest(
      name = "Accepted follow up",
      specPath = ".feature-specs/SKILL-61/spec_subtask_2_accepted.md",
      sourceStream = "stdout",
    )
    val confirmationRequest = GoalRunnerWorkerSubtaskRequest(
      name = "Confirm follow up",
      specPath = ".feature-specs/SKILL-61/spec_subtask_3_confirm.md",
      requiresOperatorConfirmation = true,
      sourceStream = "stderr",
    )

    val recorded = store.recordWorkerSubtaskRequestOutcomes(
      workflowId = "wfl-child",
      outcomes = listOf(
        GoalRunnerWorkerSubtaskRequestOutcome.Accepted(
          request = acceptedRequest,
          subtask = DecompositionSubtask(
            id = 2,
            name = "Accepted follow up",
            specPath = ".feature-specs/SKILL-61/spec_subtask_2_accepted.md",
          ),
        ),
        GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
          sourceStream = "stdout",
          reason = GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH,
          message = "unsafe path",
        ),
        GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation(
          request = confirmationRequest,
          reason = "needs approval",
        ),
      ),
      dbPathOverride = null,
    )

    assertTrue(recorded)
    val saved = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot()
    val artifacts = decodeWorkflowArtifactsForTest(saved.artifactsJson)
    val outcomes = artifacts["goal_worker_subtask_request_outcomes"] as List<*>
    val accepted = outcomes[0] as Map<*, *>
    val rejected = outcomes[1] as Map<*, *>
    val confirmation = outcomes[2] as Map<*, *>
    assertEquals("accepted", accepted["status"])
    assertEquals(2, accepted["subtask_id"])
    assertEquals("rejected", rejected["status"])
    assertEquals("unsafe_path", rejected["reason"])
    assertEquals("requires_operator_confirmation", confirmation["status"])
    assertEquals("needs approval", confirmation["reason"])
  }

  // SKILL-64 Subtask 3 (F-T01): the durable appendHistoryArtifact write seam
  // (decode artifacts_json -> append -> sequence-ordered prune -> latest-event
  // mirror) had zero coverage. These exercise it through the real outcome store.
  @Test
  fun `record progress event accumulates append-only and mirrors latest-event key`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(workflowRecord("wfl-child", emptyMap()))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    assertTrue(store.recordProgressEvent(progressEventRequest("wfl-child", sequenceNumber = 0)))
    assertTrue(store.recordProgressEvent(progressEventRequest("wfl-child", sequenceNumber = 1)))

    val artifacts = decodeWorkflowArtifactsForTest(
      requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot().artifactsJson,
    )
    val history = artifacts["goal_progress_run_history"] as List<*>
    assertEquals(2, history.size)
    assertEquals(0, (history[0] as Map<*, *>)["sequence_number"])
    assertEquals(1, (history[1] as Map<*, *>)["sequence_number"])
    val latest = artifacts["goal_progress_latest_event"] as Map<*, *>
    assertEquals(1, latest["sequence_number"])
  }

  @Test
  fun `record progress event prunes oldest entry at retention limit in sequence order`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(workflowRecord("wfl-child", emptyMap()))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    // Append more than the bounded history limit, deliberately out of order, to
    // assert sequence-ordered retention that drops the OLDEST (lowest sequence).
    val total = skillbill.workflow.model.GOAL_PROGRESS_HISTORY_LIMIT + 5
    (total - 1 downTo 0).forEach { sequence ->
      store.recordProgressEvent(progressEventRequest("wfl-child", sequenceNumber = sequence))
    }

    val artifacts = decodeWorkflowArtifactsForTest(
      requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot().artifactsJson,
    )
    val history = artifacts["goal_progress_run_history"] as List<*>
    assertEquals(skillbill.workflow.model.GOAL_PROGRESS_HISTORY_LIMIT, history.size)
    val sequences = history.map { (it as Map<*, *>)["sequence_number"] }
    assertEquals(5, sequences.first())
    assertEquals(total - 1, sequences.last())
    assertEquals(sequences.sortedBy { it as Int }, sequences)
  }

  @Test
  fun `record progress event returns false when workflow is missing`() {
    val workflows = InMemoryWorkflowStates()
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    assertFalse(store.recordProgressEvent(progressEventRequest("wfl-missing", sequenceNumber = 0)))
  }

  @Test
  fun `record progress event loud-fails through the schema validator at the write seam`() {
    // SKILL-64 Subtask 3 (F-A01): a malformed declared-progress event must be
    // rejected at the durable write seam through the injected validator port,
    // not silently persisted and then dropped by the soft supervisor read.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(workflowRecord("wfl-child", emptyMap()))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      goalProgressEventValidator = object : GoalProgressEventValidator {
        override fun validate(event: Map<String, Any?>, sourceLabel: String) {
          throw InvalidGoalProgressEventSchemaError(sourceLabel, "operation_name", "operation_name is required.")
        }
      },
    )

    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      store.recordProgressEvent(progressEventRequest("wfl-child", sequenceNumber = 0))
    }
    val artifacts = decodeWorkflowArtifactsForTest(
      requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot().artifactsJson,
    )
    assertFalse(artifacts.containsKey("goal_progress_run_history"))
    assertFalse(artifacts.containsKey("goal_progress_latest_event"))
  }

  @Test
  fun `record session accounting accumulates without mirroring a latest-event key`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(workflowRecord("wfl-child", emptyMap()))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    assertTrue(store.recordSessionAccounting(sessionAccountingRequest("wfl-child", sequenceNumber = 0)))
    assertTrue(store.recordSessionAccounting(sessionAccountingRequest("wfl-child", sequenceNumber = 1)))
    assertFalse(store.recordSessionAccounting(sessionAccountingRequest("wfl-missing", sequenceNumber = 2)))

    val artifacts = decodeWorkflowArtifactsForTest(
      requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot().artifactsJson,
    )
    val history = artifacts["goal_session_accounting"] as List<*>
    assertEquals(2, history.size)
    assertFalse(artifacts.containsKey("goal_session_accounting_latest_event"))
  }

  @Test
  fun `record attempt ledger entry prunes oldest in sequence order at retention limit`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(workflowRecord("wfl-child", emptyMap()))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val total = GOAL_ATTEMPT_LEDGER_LIMIT + 3
    (total - 1 downTo 0).forEach { sequence ->
      store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-child", sequenceNumber = sequence))
    }
    assertFalse(store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-missing", sequenceNumber = 0)))

    val artifacts = decodeWorkflowArtifactsForTest(
      requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child")).toSnapshot().artifactsJson,
    )
    val history = artifacts["goal_attempt_ledger"] as List<*>
    assertEquals(GOAL_ATTEMPT_LEDGER_LIMIT, history.size)
    val sequences = history.map { (it as Map<*, *>)["sequence_number"] }
    assertEquals(3, sequences.first())
    assertEquals(total - 1, sequences.last())
    assertEquals(sequences.sortedBy { it as Int }, sequences)
  }

  @Test
  fun `ledger sequence watermarks report the persisted max across continuation children`() {
    // SKILL-64 Subtask 3 (F-D01): the recorder seeds its monotonic counters from
    // these watermarks so a resume run continues the append-only stream.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        "wfl-child",
        mapOf("goal_continuation" to mapOf("issue_key" to "SKILL-64", "subtask_id" to 1)),
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )
    store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-child", sequenceNumber = 0))
    store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-child", sequenceNumber = 1))
    store.recordSessionAccounting(sessionAccountingRequest("wfl-child", sequenceNumber = 0))
    // SKILL-64 Subtask 3 (F-NT03): goal_progress is the exact stream the
    // production supervisor emitter seeds from (GoalRunner.kt watermarkSeed =
    // maxProgressSequence). Record events out of order to prove the watermark is
    // the MAX of the persisted goal_progress_run_history, not the last write.
    store.recordProgressEvent(progressEventRequest("wfl-child", sequenceNumber = 3))
    store.recordProgressEvent(progressEventRequest("wfl-child", sequenceNumber = 2))

    val watermarks = store.ledgerSequenceWatermarks("SKILL-64")

    assertEquals(1, watermarks.maxLedgerSequence)
    assertEquals(0, watermarks.maxAccountingSequence)
    assertEquals(3, watermarks.maxProgressSequence)
    assertNull(store.ledgerSequenceWatermarks("SKILL-other").maxLedgerSequence)
    // No goal_progress recorded for the unrelated issue, so its progress
    // watermark must be absent (null) rather than 0.
    assertNull(store.ledgerSequenceWatermarks("SKILL-other").maxProgressSequence)
  }

  @Test
  fun `subtask resume alignment keeps later running step over stale manifest step`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureImplementWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-child", "fis-001", "preplan")
    val running = testWorkflowEngine.updateRecord(
      definition,
      opened,
      skillbill.workflow.model.WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "preplan", "status" to "blocked", "attempt_count" to 1),
          mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = null,
        sessionId = "fis-001",
      ),
    )
    workflows.saveFeatureImplementWorkflow(running.toRecord())
    val database = FakeDatabaseSessionFactory(workflows)

    val aligned = database.transaction(null) { unitOfWork ->
      testWorkflowEngine.alignSubtaskResumeStep(running, "preplan", unitOfWork)
    }

    assertEquals("implement", aligned.currentStepId)
    val saved = requireNotNull(workflows.getFeatureImplementWorkflow("wfl-child"))
    assertEquals("implement", saved.currentStepId)
    val steps = decodeWorkflowStepsForTest(saved.stepsJson)
    assertEquals("completed", steps.getValue("preplan"))
    assertEquals("running", steps.getValue("implement"))
  }
}

/**
 * SKILL-64 Subtask 4 (AC1, AC2): named ceiling for the compact workflow-update
 * acknowledgement. The compact ack carries only summary fields + read-only
 * guidance, so it stays tiny regardless of how large the persisted artifacts
 * are; echoing the full durable state back would blow past this.
 */
private const val COMPACT_UPDATE_ACK_PAYLOAD_BYTE_CEILING = 1024

private fun decodeWorkflowStepsForTest(stepsJson: String): Map<String, String> {
  val element = skillbill.contracts.JsonSupport.json.parseToJsonElement(stepsJson)
  val value = skillbill.contracts.JsonSupport.jsonElementToValue(element) as List<*>
  return value.associate { raw ->
    val item = raw as Map<*, *>
    item["step_id"].toString() to item["status"].toString()
  }
}

private fun decodeWorkflowArtifactsForTest(artifactsJson: String): Map<String, Any?> {
  val element = skillbill.contracts.JsonSupport.json.parseToJsonElement(artifactsJson)
  return requireNotNull(
    skillbill.contracts.JsonSupport.anyToStringAnyMap(
      skillbill.contracts.JsonSupport.jsonElementToValue(element),
    ),
  )
}

private fun progressEventRequest(workflowId: String, sequenceNumber: Int): GoalRunnerProgressEventRecordRequest =
  GoalRunnerProgressEventRecordRequest(
    workflowId = workflowId,
    event = GoalProgressEvent(
      eventKind = GoalProgressEventKind.PHASE_STARTED,
      workflowId = workflowId,
      workflowPhase = "implement",
      processAlive = true,
      sequenceNumber = sequenceNumber,
      timestamp = "2026-06-02T10:00:0${sequenceNumber % 10}Z",
    ),
  )

private fun sessionAccountingRequest(
  workflowId: String,
  sequenceNumber: Int,
): GoalRunnerSessionAccountingRecordRequest = GoalRunnerSessionAccountingRecordRequest(
  workflowId = workflowId,
  accounting = GoalSessionAccounting(
    subtaskId = 1,
    phase = "goal_runner_supervision",
    available = false,
    sequenceNumber = sequenceNumber,
    timestamp = "2026-06-02T10:00:0${sequenceNumber % 10}Z",
    unavailableReason = "no provider accounting",
  ),
)

private fun attemptLedgerRequest(workflowId: String, sequenceNumber: Int): GoalRunnerAttemptLedgerRecordRequest =
  GoalRunnerAttemptLedgerRecordRequest(
    workflowId = workflowId,
    entry = GoalAttemptLedgerEntry(
      action = GoalAttemptLedgerAction.CHILD_ACTIVATION,
      sequenceNumber = sequenceNumber,
      timestamp = "2026-06-02T10:00:0${sequenceNumber % 10}Z",
    ),
  )

private fun assertProgressEventAcknowledgement(ok: WorkflowUpdateResult.Ok) {
  assertEquals("running", ok.acknowledgement.workflowStatus)
  assertEquals("implement", ok.acknowledgement.currentStepId)
  assertEquals(listOf("implement"), ok.acknowledgement.updatedStepIds)
  assertEquals(
    listOf(
      "goal_continuation",
      "goal_observability_latest_event",
      "goal_observability_run_history",
      "progress_event",
    ),
    ok.acknowledgement.updatedArtifactKeys,
  )
}

private fun assertPersistedProgressEventArtifacts(persisted: WorkflowGetResult.Ok, workflowId: String) {
  val latest = persisted.snapshot.artifacts["goal_observability_latest_event"] as Map<*, *>
  val history = persisted.snapshot.artifacts["goal_observability_run_history"] as List<*>
  assertEquals("SKILL-61", latest["issue_key"])
  assertEquals(1, latest["subtask_id"])
  assertEquals("implement", latest["workflow_phase"])
  assertEquals("phase_subagent", latest["worker_role"])
  assertEquals("durable_progress", latest["liveness_class"])
  assertEquals("editing runtime files", latest["activity_summary"])
  assertEquals(workflowId, latest["workflow_id"])
  assertEquals(7, latest["sequence_number"])
  assertEquals(mapOf("files_changed" to 0, "insertions" to 0, "deletions" to 0), latest["diff_stat"])
  assertEquals(1, history.size)
  assertTrue(persisted.snapshot.artifacts.containsKey("progress_event"))
}

private val testWorkflowEngine: WorkflowEngine = WorkflowEngine(testWorkflowSnapshotValidator)

private val testGoalObservabilityEventValidator: GoalObservabilityEventValidator =
  object : GoalObservabilityEventValidator {
    override fun validate(event: Map<String, Any?>, sourceLabel: String) = Unit
  }

private fun workflowRecord(workflowId: String, artifactsPatch: Map<String, Any?>): WorkflowStateRecord {
  val definition = FeatureImplementWorkflowDefinition.definition
  val opened = testWorkflowEngine.openRecord(definition, workflowId, "fis-001", "assess")
  return testWorkflowEngine.updateRecord(
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

private fun completeDecompositionRuntime(): DecompositionManifest = decompositionRuntime(status = "complete").copy(
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "none"),
  subtasks = listOf(
    DecompositionSubtask(
      id = 1,
      name = "install-policy-foundation",
      specPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy/spec_subtask_1.md",
      status = "complete",
      workflowId = "wfl-child",
      commitSha = "sha-complete",
      lastResumableStep = "commit_push",
    ),
  ),
)

internal class FakeDatabaseSessionFactory(
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

internal class InMemoryWorkflowStates : WorkflowStateRepository {
  private val implement = mutableMapOf<String, WorkflowStateRecord>()
  private val verify = mutableMapOf<String, WorkflowStateRecord>()
  private val taskRuntime = mutableMapOf<String, WorkflowStateRecord>()

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
  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    implement[workflowId] ?: taskRuntime[workflowId]
  override fun getFeatureTaskWorkflowAsMode(workflowId: String, mode: FeatureTaskWorkflowMode): WorkflowStateRecord? {
    val row = getFeatureTaskWorkflow(workflowId) ?: return null
    val effectiveMode = row.mode ?: FeatureTaskWorkflowMode.PROSE
    if (effectiveMode != mode) {
      throw InvalidWorkflowStateSchemaError(
        "Feature-task workflow '$workflowId' is mode='${effectiveMode.wireValue}', not '${mode.wireValue}'.",
      )
    }
    return row
  }
  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    taskRuntime[row.workflowId] = row
  }
  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntime[workflowId]
  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntime.values.toList().take(limit)
  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? = taskRuntime.values.lastOrNull()
}

class DecompositionDiskBootstrapTest {
  @Test
  fun `continueDecomposedParentByIssueKey bootstraps from disk when no DB parent row exists`() {
    val repoRoot = Files.createTempDirectory("skillbill-disk-bootstrap")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest = DecompositionManifest(
      issueKey = "SKILL-TEST",
      featureName = "test-feature",
      parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
      status = "in_progress",
      executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
      baseBranch = "main",
      featureBranch = "",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
      subtasks = listOf(
        DecompositionSubtask(
          id = 1,
          name = "first-subtask",
          specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
          status = "pending",
        ),
      ),
    )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestFileStore),
    )
    val workflows = InMemoryWorkflowStates()
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation = DecompositionWorkflowContinuation(
      engine = testWorkflowEngine,
      gitOperations = NoopWorkflowGitOperations,
      validator = testDecompositionManifestValidator,
      fileStore = TestDecompositionManifestFileStore,
      repoRootProvider = { repoRoot },
    )

    val result = db.transaction<ContinuationStepResult>(null) { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }

    assertFalse(
      result.result is WorkflowContinueResult.UnknownWorkflow,
      "Expected disk bootstrap to resolve the manifest; got UnknownWorkflow instead",
    )
    assertTrue(
      workflows.listFeatureImplementWorkflows(Int.MAX_VALUE).size >= 2,
      "Expected parent bootstrap row and child subtask row in DB",
    )
  }

  @Test
  fun `continueDecomposedParentByIssueKey returns UnknownWorkflow when disk has no manifest for issue key`() {
    val repoRoot = Files.createTempDirectory("skillbill-disk-bootstrap-miss")
    val workflows = InMemoryWorkflowStates()
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation = DecompositionWorkflowContinuation(
      engine = testWorkflowEngine,
      gitOperations = NoopWorkflowGitOperations,
      validator = testDecompositionManifestValidator,
      fileStore = TestDecompositionManifestFileStore,
      repoRootProvider = { repoRoot },
    )

    val result = db.transaction<ContinuationStepResult>(null) { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-MISSING", unitOfWork)
    }

    assertIs<WorkflowContinueResult.UnknownWorkflow>(result.result)
  }
}
