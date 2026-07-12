package skillbill.application

import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowOpenResult
import skillbill.application.workflow.WorkflowService
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
      service.open(WorkflowFamilyKind.TASK_PROSE, issueKey = "  SKILL-117  "),
    )
    val runtime = assertIs<WorkflowOpenResult.Ok>(
      service.open(WorkflowFamilyKind.TASK_RUNTIME, issueKey = " SKILL-118 "),
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

    assertFailsWith<IllegalArgumentException> {
      service.open(WorkflowFamilyKind.TASK_PROSE, issueKey = "SKILL-117\nspoofed")
    }
    assertFailsWith<IllegalArgumentException> {
      service.open(WorkflowFamilyKind.TASK_PROSE, issueKey = "S".repeat(129))
    }
  }
}
