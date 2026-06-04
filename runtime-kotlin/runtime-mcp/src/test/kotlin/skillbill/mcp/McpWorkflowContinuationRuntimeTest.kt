package skillbill.mcp

import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    val continued = McpWorkflowRuntime.continueWorkflow(
      WorkflowFamilyKind.IMPLEMENT,
      "SKILL-51",
      fixture.context,
      subtaskId = 1,
    )

    assertEquals("ok", continued["status"])
    assertEquals(1, continued["decomposition_subtask_id"])
    assertEquals(fixture.subtaskSpec.toString(), continued["decomposition_subtask_spec_path"])
    assertEquals("preplan", continued["resume_step_id"])
    assertTrue(continued.containsKey("current_step_artifacts"))
    val continuedWorkflowId = continued["workflow_id"] as String
    assertEquals(
      "skill-bill --db '${fixture.dbPath}' workflow show '$continuedWorkflowId' --format json",
      continued["read_only_full_state_command"],
    )
    assertFalse(continued.containsKey("artifacts"))
    assertFalse(continued.containsKey("steps"))
  }

  @Test
  fun `mcp workflow continue handler forwards requested decomposed subtask constraint`() {
    val fixture = mcpDecompositionFixture()
    val opened = McpWorkflowRuntime.open(
      WorkflowFamilyKind.IMPLEMENT,
      sessionId = "fis-mcp-decomp",
      context = fixture.context,
    )
    val workflowId = opened["workflow_id"] as String

    McpWorkflowRuntime.update(WorkflowFamilyKind.IMPLEMENT, fixture.updateRequest(workflowId), fixture.context)
    val continued = workflowContinue(
      WorkflowFamilyKind.IMPLEMENT,
      mapOf("issue_key" to "SKILL-51", "subtask_id" to 2),
      fixture.context,
    )

    assertEquals("error", continued["status"])
    assertEquals("blocked", continued["continue_status"])
    assertEquals(2, continued["decomposition_subtask_id"])
    assertEquals("Requested subtask 2 is not the next runnable subtask for SKILL-51.", continued["blocked_reason"])
  }
}

private data class McpDecompositionFixture(
  val context: McpRuntimeContext,
  val dbPath: Path,
  val parentSpec: Path,
  val subtaskSpec: Path,
  val secondSubtaskSpec: Path,
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
          mapOf(
            "id" to 2,
            "name" to "runtime",
            "spec_path" to secondSubtaskSpec.toString(),
            "depends_on" to listOf(1),
          ),
        ),
      ),
    ),
  )
}

private fun mcpDecompositionFixture(): McpDecompositionFixture {
  val tempDir = Files.createTempDirectory("skillbill-mcp-decomposition-continue")
  val dbPath = tempDir.resolve("metrics.db")
  val configPath = tempDir.resolve("config.json")
  Files.writeString(configPath, """{"install_id":"test","telemetry":{"level":"off"}}""")
  val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
  val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
  val secondSubtaskSpec = parentSpec.parent.resolve("spec_subtask_2_runtime.md")
  Files.createDirectories(parentSpec.parent)
  Files.writeString(parentSpec, "# Parent")
  Files.writeString(subtaskSpec, "---\nstatus: Pending\n---\n\n# Subtask")
  Files.writeString(secondSubtaskSpec, "---\nstatus: Pending\n---\n\n# Subtask")
  return McpDecompositionFixture(
    context = McpRuntimeContext(
      environment = mapOf(
        "SKILL_BILL_REVIEW_DB" to dbPath.toString(),
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
      ),
      userHome = tempDir,
      workflowGitOperations = TestWorkflowGitOperations,
    ),
    dbPath = dbPath,
    parentSpec = parentSpec,
    subtaskSpec = subtaskSpec,
    secondSubtaskSpec = secondSubtaskSpec,
  )
}

private object TestWorkflowGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-commit")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-commit")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = "ok")

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = "ok")
}
