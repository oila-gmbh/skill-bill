package skillbill.cli

import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CliWorkflowContinuationRuntimeTest {
  @Test
  fun `workflow continue accepts decomposed parent issue key`() {
    val fixture = cliDecompositionFixture()
    val opened = runJson(fixture.openCommand(), fixture.context)
    val workflowId = opened["workflow_id"] as String

    runJson(fixture.updateCommand(workflowId), fixture.context)
    val continued = runJson(fixture.continueCommand(), fixture.context)

    assertEquals("ok", continued["status"])
    assertEquals(1, continued["decomposition_subtask_id"])
    assertEquals(fixture.subtaskSpec.toString(), continued["decomposition_subtask_spec_path"])
  }
}

private data class CliDecompositionFixture(
  val dbPath: Path,
  val context: CliRuntimeContext,
  val parentSpec: Path,
  val subtaskSpec: Path,
) {
  fun openCommand(): List<String> = listOf("--db", dbPath.toString(), "workflow", "open", "--format", "json")

  fun updateCommand(workflowId: String): List<String> = listOf(
    "--db",
    dbPath.toString(),
    "workflow",
    "update",
    workflowId,
    "--workflow-status",
    "running",
    "--current-step-id",
    "plan",
    "--step-updates",
    """[{"step_id":"plan","status":"completed","attempt_count":1}]""",
    "--artifacts-patch",
    artifactsPatch(),
    "--format",
    "json",
  )

  fun continueCommand(): List<String> =
    listOf("--db", dbPath.toString(), "workflow", "continue", "SKILL-51", "--format", "json")

  private fun artifactsPatch(): String = jsonString(
    mapOf(
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

private fun cliDecompositionFixture(): CliDecompositionFixture {
  val tempDir = Files.createTempDirectory("skillbill-cli-decomposition-continue")
  val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
  val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
  Files.createDirectories(parentSpec.parent)
  Files.writeString(parentSpec, "# Parent")
  Files.writeString(subtaskSpec, "---\nstatus: Pending\n---\n\n# Subtask")
  return CliDecompositionFixture(
    dbPath = tempDir.resolve("metrics.db"),
    context = CliRuntimeContext(userHome = tempDir, workflowGitOperations = TestWorkflowGitOperations),
    parentSpec = parentSpec,
    subtaskSpec = subtaskSpec,
  )
}

private fun runJson(arguments: List<String>, context: CliRuntimeContext): Map<String, Any?> {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
}

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = requireNotNull(JsonSupport.parseObjectOrNull(rawJson))
  return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
}

private fun jsonString(value: Any?): String = JsonSupport.json.encodeToString(
  kotlinx.serialization.json.JsonElement.serializer(),
  JsonSupport.valueToJsonElement(value),
)

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
