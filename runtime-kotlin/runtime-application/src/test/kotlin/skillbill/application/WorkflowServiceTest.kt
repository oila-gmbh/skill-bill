package skillbill.application

import skillbill.application.model.WorkflowContinueResult
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowGetResult
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.application.model.WorkflowUpdateResult
import skillbill.error.InvalidGoalObservabilityEventSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
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
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
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
    val latest = ok.snapshot.artifacts["goal_observability_latest_event"] as Map<*, *>
    val history = ok.snapshot.artifacts["goal_observability_run_history"] as List<*>
    assertEquals("SKILL-61", latest["issue_key"])
    assertEquals(1, latest["subtask_id"])
    assertEquals("implement", latest["workflow_phase"])
    assertEquals("phase_subagent", latest["worker_role"])
    assertEquals("durable_progress", latest["liveness_class"])
    assertEquals("editing runtime files", latest["activity_summary"])
    assertEquals(7, latest["sequence_number"])
    assertEquals(1, history.size)
    assertTrue(ok.snapshot.artifacts.containsKey("progress_event"))
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
      contract_version: "0.2"
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
  fun `goal runner progress loud-fails malformed goal observability latest event`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch = mapOf(
          "goal_observability_latest_event" to mapOf(
            "contract_version" to "0.1",
            "issue_key" to "SKILL-61",
          ),
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

    assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      store.progress("wfl-child")
    }
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

private fun decodeWorkflowStepsForTest(stepsJson: String): Map<String, String> {
  val element = skillbill.contracts.JsonSupport.json.parseToJsonElement(stepsJson)
  val value = skillbill.contracts.JsonSupport.jsonElementToValue(element) as List<*>
  return value.associate { raw ->
    val item = raw as Map<*, *>
    item["step_id"].toString() to item["status"].toString()
  }
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
