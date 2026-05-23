package skillbill.mcp

import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class McpWorkflowContinuationRuntimeTest {
  @Test
  fun `mcp workflow continue accepts decomposed parent issue key`() {
    val fixture = mcpDecompositionFixture()
    val opened = McpWorkflowRuntime.open(
      WorkflowFamilyKind.IMPLEMENT,
      sessionId = "fis-mcp-decomp",
      context = fixture.context,
    )
    val workflowId = opened["workflow_id"] as String

    McpWorkflowRuntime.update(WorkflowFamilyKind.IMPLEMENT, fixture.updateRequest(workflowId), fixture.context)
    val continued = McpWorkflowRuntime.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, "SKILL-51", fixture.context)

    assertEquals("ok", continued["status"])
    assertEquals(1, continued["decomposition_subtask_id"])
    assertEquals(fixture.subtaskSpec.toString(), continued["decomposition_subtask_spec_path"])
  }
}

private data class McpDecompositionFixture(
  val context: McpRuntimeContext,
  val parentSpec: Path,
  val subtaskSpec: Path,
) {
  fun updateRequest(workflowId: String): WorkflowUpdateRequest = WorkflowUpdateRequest(
    workflowId = workflowId,
    workflowStatus = "running",
    currentStepId = "plan",
    stepUpdates = listOf(mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1)),
    artifactsPatch = mapOf(
      "branch" to mapOf("branch" to "feat/SKILL-51-demo"),
      "plan" to mapOf(
        "mode" to "decompose",
        "parent_spec_path" to parentSpec.toString(),
        "recommended_first_subtask_id" to 1,
        "subtasks" to listOf(
          mapOf(
            "id" to 1,
            "name" to "foundation",
            "spec_path" to subtaskSpec.toString(),
            "depends_on" to emptyList<Int>(),
          ),
        ),
      ),
    ),
  )
}

private fun mcpDecompositionFixture(): McpDecompositionFixture {
  val tempDir = Files.createTempDirectory("skillbill-mcp-decomposition-continue")
  val configPath = tempDir.resolve("config.json")
  Files.writeString(configPath, """{"install_id":"test","telemetry":{"level":"off"}}""")
  val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
  val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
  Files.createDirectories(parentSpec.parent)
  Files.writeString(parentSpec, "# Parent")
  Files.writeString(subtaskSpec, "---\nstatus: Pending\n---\n\n# Subtask")
  return McpDecompositionFixture(
    context = McpRuntimeContext(
      environment = mapOf(
        "SKILL_BILL_REVIEW_DB" to tempDir.resolve("metrics.db").toString(),
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
      ),
      userHome = tempDir,
      workflowGitOperations = TestWorkflowGitOperations,
    ),
    parentSpec = parentSpec,
    subtaskSpec = subtaskSpec,
  )
}

private object TestWorkflowGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-commit")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)
}
