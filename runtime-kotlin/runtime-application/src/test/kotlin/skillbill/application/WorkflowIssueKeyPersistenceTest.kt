package skillbill.application

import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.workflow.WorkflowService
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
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
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )

    val prose = assertIs<WorkflowOpenResult.Ok>(
      service.openFeatureTask(
        WorkflowFamilyKind.TASK_PROSE,
        issueKey = "  SKILL-117  ",
        repositoryIdentity = "repo-root-realpath-v1:/test/repository",
        governedSpecPath = ".feature-specs/SKILL-117/spec.md",
      ),
    )
    val runtime = assertIs<WorkflowOpenResult.Ok>(
      service.openFeatureTask(
        WorkflowFamilyKind.TASK_RUNTIME,
        issueKey = " SKILL-118 ",
        repositoryIdentity = "repo-root-realpath-v1:/test/repository",
        governedSpecPath = ".feature-specs/SKILL-118/spec.md",
      ),
    )
    val verify = assertIs<WorkflowOpenResult.Ok>(
      service.open(WorkflowFamilyKind.VERIFY, issueKey = " SKILL-119 "),
    )

    assertEquals("SKILL-117", assertNotNull(workflows.getFeatureImplementWorkflow(prose.workflowId)).issueKey)
    assertEquals("SKILL-118", assertNotNull(workflows.getFeatureTaskRuntimeWorkflow(runtime.workflowId)).issueKey)
    assertEquals("SKILL-119", assertNotNull(workflows.getFeatureVerifyWorkflow(verify.workflowId)).issueKey)
  }

  @Test
  fun `opening a workflow rejects control-bearing and oversized issue keys`() {
    val workflows = InMemoryWorkflowStates()
    val service = WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
    )

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      service.openFeatureTask(
        WorkflowFamilyKind.TASK_PROSE,
        issueKey = "SKILL-117\nspoofed",
        repositoryIdentity = "repo-root-realpath-v1:/test/repository",
        governedSpecPath = ".feature-specs/SKILL-117/spec.md",
      )
    }
    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      service.openFeatureTask(
        WorkflowFamilyKind.TASK_PROSE,
        issueKey = "S".repeat(129),
        repositoryIdentity = "repo-root-realpath-v1:/test/repository",
        governedSpecPath = ".feature-specs/SKILL-117/spec.md",
      )
    }
  }

  @Test
  fun `runtime workflow reopen heals a missing issue key and rejects a conflicting normalized key`() {
    val workflows = InMemoryWorkflowStates()
    val recorder = FeatureTaskRuntimePhaseRecorder(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      handoffEnvelopeValidator = AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
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
