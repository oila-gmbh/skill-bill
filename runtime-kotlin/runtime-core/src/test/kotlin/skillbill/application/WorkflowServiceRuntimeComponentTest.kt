package skillbill.application

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult.Ok
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.model.RuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import skillbill.application.workflow.model.WorkflowOpenResult.Ok as WorkflowOpenResultOk

class WorkflowServiceRuntimeComponentTest {
  @Test
  fun `runtime component workflow service writes decomposition projection through configured file store`() {
    val tempDir = Files.createTempDirectory("skillbill-component-decomposition")
    val dbPath = tempDir.resolve("metrics.db")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    val service =
      RuntimeComponent::class.create(
        RuntimeContext(
          dbPathOverride = dbPath.toString(),
          environment = emptyMap(),
          userHome = tempDir,
        ),
      ).workflowService
    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
    val workflowId =
      (opened as WorkflowOpenResultOk).workflowId

    val updated =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = workflowId,
          workflowStatus = "running",
          currentStepId = "plan",
          stepUpdates = listOf(mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1)),
          artifactsPatch = decompositionPlanPatch(parentSpec, subtaskSpec),
        ),
        dbOverride = null,
      )

    val manifest = parentSpec.parent.resolve("decomposition-manifest.yaml")
    assertTrue(updated is Ok)
    assertTrue(Files.isRegularFile(manifest), "RuntimeComponent must bind a writable decomposition manifest store.")
    assertTrue(Files.readString(manifest).contains("same_branch_commit_per_subtask"))
  }

  private fun decompositionPlanPatch(parentSpec: Path, subtaskSpec: Path): Map<String, Any?> = mapOf(
    "branch" to mapOf("branch" to "feat/SKILL-51-demo"),
    "plan" to linkedMapOf(
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
  )
}
