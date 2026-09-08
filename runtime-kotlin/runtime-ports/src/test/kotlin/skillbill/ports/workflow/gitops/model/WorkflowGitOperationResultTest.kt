package skillbill.ports.workflow.gitops.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowGitOperationResultTest {
  @Test
  fun `wire mapping preserves case payloads and canonical tokens`() {
    val ok = WorkflowGitOperationResult.fromWire("ok", value = "value", error = "diagnostic")
    val failed = WorkflowGitOperationResult.fromWire("error", value = "output", error = "failure")

    assertTrue(ok is WorkflowGitOperationResult.Ok)
    assertEquals("value", ok.value)
    assertEquals("diagnostic", ok.error)
    assertEquals("ok", ok.wireValue)
    assertTrue(failed is WorkflowGitOperationResult.Failed)
    assertEquals("output", failed.value)
    assertEquals("failure", failed.error)
    assertEquals("error", failed.wireValue)
  }

  @Test
  fun `wire mapping preserves unknown diagnostics as failed`() {
    val result = WorkflowGitOperationResult.fromWire("unknown", value = "output")
    val diagnosed = WorkflowGitOperationResult.fromWire("unknown", error = "diagnostic")

    assertEquals(WorkflowGitOperationResult.Failed(value = "output", error = "unknown"), result)
    assertEquals(WorkflowGitOperationResult.Failed(value = "", error = "diagnostic"), diagnosed)
  }

  @Test
  fun `structured result status owns canonical wire mapping`() {
    assertEquals("ok", WorkflowGitOperationStatus.OK.wireValue)
    assertEquals("error", WorkflowGitOperationStatus.ERROR.wireValue)
    assertEquals(WorkflowGitOperationStatus.OK, WorkflowGitOperationStatus.fromWire("ok"))
    assertEquals(WorkflowGitOperationStatus.ERROR, WorkflowGitOperationStatus.fromWire("error"))
    assertEquals(null, WorkflowGitOperationStatus.fromWire("unknown"))
  }

  @Test
  fun `nothing-to-commit markers are read from either payload`() {
    assertTrue(WorkflowGitOperationResult.Failed(value = "nothing to commit").recordsNothingToCommit())
    assertTrue(WorkflowGitOperationResult.Failed(error = "no changes added to commit").recordsNothingToCommit())
    assertTrue(WorkflowGitOperationResult.Failed(value = "nothing added to commit").recordsNothingToCommit())
    assertTrue(!WorkflowGitOperationResult.Failed(error = "permission denied").recordsNothingToCommit())
  }
}
