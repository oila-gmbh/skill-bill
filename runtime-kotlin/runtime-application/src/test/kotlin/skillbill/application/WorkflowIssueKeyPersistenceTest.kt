package skillbill.application

import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.goalrunner.testPhaseRecorder
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceDeps
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.openFeatureTask
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class WorkflowIssueKeyPersistenceTest {
  @Test
  fun `opening every issue keyed workflow persists its normalized issue key in workflow metadata`() {
    val workflows = InMemoryWorkflowStates()
    val service = WorkflowService(
      WorkflowServiceDeps(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
      ),
    )

    val firstRuntime = assertIs<WorkflowOpenResult.Ok>(
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "  SKILL-117  ",
          repositoryIdentity = "repo-root-realpath-v1:/test/repository",
          governedSpecPath = ".feature-specs/SKILL-117/spec.md",
        ),
      ),
    )
    val secondRuntime = assertIs<WorkflowOpenResult.Ok>(
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = " SKILL-118 ",
          repositoryIdentity = "repo-root-realpath-v1:/test/repository",
          governedSpecPath = ".feature-specs/SKILL-118/spec.md",
        ),
      ),
    )
    val verify = assertIs<WorkflowOpenResult.Ok>(
      service.open(WorkflowServiceOpenArgs(kind = WorkflowFamilyKind.VERIFY, issueKey = " SKILL-119 ")),
    )

    assertEquals("SKILL-117", assertNotNull(workflows.getFeatureTaskRuntimeWorkflow(firstRuntime.workflowId)).issueKey)
    assertEquals("SKILL-118", assertNotNull(workflows.getFeatureTaskRuntimeWorkflow(secondRuntime.workflowId)).issueKey)
    assertEquals("SKILL-119", assertNotNull(workflows.getFeatureVerifyWorkflow(verify.workflowId)).issueKey)
  }

  @Test
  fun `opening a workflow rejects control-bearing and oversized issue keys`() {
    val workflows = InMemoryWorkflowStates()
    val service = WorkflowService(
      WorkflowServiceDeps(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
      ),
    )

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "SKILL-117\nspoofed",
          repositoryIdentity = "repo-root-realpath-v1:/test/repository",
          governedSpecPath = ".feature-specs/SKILL-117/spec.md",
        ),
      )
    }
    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "S".repeat(129),
          repositoryIdentity = "repo-root-realpath-v1:/test/repository",
          governedSpecPath = ".feature-specs/SKILL-117/spec.md",
        ),
      )
    }
  }

  @Test
  fun `runtime workflow reopen heals a missing issue key and rejects a conflicting normalized key`() {
    val workflows = InMemoryWorkflowStates()
    val recorder = testPhaseRecorder(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      handoffEnvelopeValidator = AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      handoffFoundationValidator = AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    )

    recorder.ensureWorkflowOpen("wftr-117", "session-117")
    recorder.ensureWorkflowOpen("wftr-117", "session-117", issueKey = " SKILL-117 ")
    recorder.ensureWorkflowOpen("wftr-117", "session-117", issueKey = "SKILL-117")

    val healed = assertNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-117"))
    assertEquals("SKILL-117", healed.issueKey)

    val conflict = assertFailsWith<WorkflowIssueKeyConflictError> {
      recorder.ensureWorkflowOpen("wftr-117", "session-117", issueKey = "SKILL-118")
    }
    assertEquals("wftr-117", conflict.workflowId)
    assertEquals("SKILL-117", conflict.persistedIssueKey)
    assertEquals("SKILL-118", conflict.requestedIssueKey)
    assertEquals("SKILL-117", assertNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-117")).issueKey)
  }
}
