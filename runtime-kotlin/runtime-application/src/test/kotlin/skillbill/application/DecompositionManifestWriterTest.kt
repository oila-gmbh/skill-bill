package skillbill.application

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.toWireMap
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecompositionManifestWriterTest {
  @Test
  fun `decomposition planning result writes validated same branch manifest beside parent spec`() {
    val repoRoot = Files.createTempDirectory("skillbill-decomposition-manifest")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val result = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    assertNotNull(result)
    assertTrue(Files.isRegularFile(result.manifestPath))
    assertEquals(parentSpecPath.parent.resolve("decomposition-manifest.yaml"), result.manifestPath)

    val loaded = loadDecompositionManifest(result.manifestPath)
    assertEquals("same_branch_commit_per_subtask", loaded.executionModel.wireValue)
    assertEquals("feature/SKILL-51-decomposition", loaded.featureBranch)
    assertEquals(emptyList(), loaded.stackBranches)
    assertEquals(1, loaded.currentSubtaskIntent.subtaskId)
    assertEquals("start", loaded.currentSubtaskIntent.action)
    assertEquals(null, loaded.subtasks.first().workflowId)
    assertEquals(null, loaded.subtasks.first().reviewResult)
  }

  @Test
  fun `workflow update writes stacked branch manifest without same branch feature branch`() {
    val repoRoot = Files.createTempDirectory("skillbill-stacked-decomposition")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val result = DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = "{}",
      artifactsPatch = mapOf("plan" to stackedDecompositionPlan()),
    )

    assertNotNull(result)
    assertEquals("stacked_branches", result.manifest.executionModel.wireValue)
    assertEquals(null, result.manifest.featureBranch)
    assertEquals(
      listOf("feature/SKILL-51-01-foundation", "feature/SKILL-51-02-runtime"),
      result.manifest.stackBranches.map { it.branch },
    )
  }

  @Test
  fun `workflow update records runtime state on matching subtask and projects markdown status`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-state")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val secondSubtaskSpec = parentSpecPath.parent.resolve("spec_subtask_2_runtime.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    Files.writeString(
      secondSubtaskSpec,
      """
      ---
      status: Pending
      ---

      # Runtime
      """.trimIndent(),
    )
    DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val result = DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = runtimeArtifactsJson(secondSubtaskSpec),
      artifactsPatch = mapOf("review_result" to mapOf("finding_count" to 0)),
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-2",
        workflowStatus = "running",
        currentStepId = "audit",
        stepUpdates = listOf(mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    val runtimeSubtask = result.manifest.subtasks.single { it.id == 2 }
    assertEquals("in_progress", runtimeSubtask.status)
    assertEquals("feature/SKILL-51-decomposition", runtimeSubtask.branch)
    assertEquals("wfl-subtask-2", runtimeSubtask.workflowId)
    assertEquals(mapOf("finding_count" to 0), runtimeSubtask.reviewResult)
    assertEquals("audit", runtimeSubtask.lastResumableStep)
    assertContains(Files.readString(secondSubtaskSpec), "status: In Progress")
  }

  @Test
  fun `runtime update projects completed subtask status and commit sha from durable runtime`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-completed-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    Files.writeString(
      subtaskSpec,
      """
      ---
      status: In Progress
      ---

      # Foundation
      """.trimIndent(),
    )
    val initial = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    assertNotNull(initial)
    val completed = initial.manifest.copy(
      subtasks = initial.manifest.subtasks.map { subtask ->
        if (subtask.id == 1) {
          subtask.copy(status = "complete", commitSha = "commit-subtask-1", workflowId = "wfl-subtask-1")
        } else {
          subtask
        }
      },
    )

    val result = DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = durableRuntimeArtifactsJson(completed, subtaskSpec),
      artifactsPatch = null,
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-1",
        workflowStatus = "completed",
        currentStepId = "complete",
        stepUpdates = listOf(mapOf("step_id" to "complete", "status" to "completed", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    val subtask = result.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", subtask.status)
    assertEquals("commit-subtask-1", subtask.commitSha)
    assertContains(Files.readString(subtaskSpec), "status: Complete")
  }

  @Test
  fun `runtime update with explicit unmatched spec path does not fall back to current subtask`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-unmatched-spec")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val result = DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = runtimeArtifactsJson(parentSpecPath.parent.resolve("missing-subtask.md")),
      artifactsPatch = mapOf("review_result" to mapOf("finding_count" to 0)),
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-wrong-subtask",
        workflowStatus = "running",
        currentStepId = "audit",
        stepUpdates = listOf(mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    assertEquals(listOf("pending", "pending"), result.manifest.subtasks.map { it.status })
    assertEquals(null, result.manifest.subtasks.first().workflowId)
  }

  @Test
  fun `skipping an intermediate tracked step keeps subtask in progress when workflow advances`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-intermediate-skip")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val result = DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = runtimeArtifactsJson(subtaskSpec),
      artifactsPatch = null,
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-1",
        workflowStatus = "running",
        currentStepId = "audit",
        stepUpdates = listOf(mapOf("step_id" to "review", "status" to "skipped", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    assertEquals("in_progress", result.manifest.subtasks.first().status)
    assertEquals("resume", result.manifest.currentSubtaskIntent.action)
  }

  @Test
  fun `runtime projection is regenerated from durable decomposition runtime when manifest file is missing`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-regenerate")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    assertNotNull(initial)
    Files.delete(initial.manifestPath)

    val result = DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = durableRuntimeArtifactsJson(initial.manifest, subtaskSpec),
      artifactsPatch = mapOf("validation_result" to mapOf("passed" to true)),
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-1",
        workflowStatus = "running",
        currentStepId = "validate",
        stepUpdates = listOf(mapOf("step_id" to "validate", "status" to "completed", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    assertTrue(Files.isRegularFile(initial.manifestPath))
    assertEquals(mapOf("passed" to true), result.manifest.subtasks.first().validationResult)
  }

  @Test
  fun `runtime updates prefer durable decomposition runtime over stale filesystem projection`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-durable-first")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    assertNotNull(initial)
    val durable = initial.manifest.copy(
      subtasks = initial.manifest.subtasks.map { subtask ->
        if (subtask.id == 1) {
          subtask.copy(status = "in_progress", commitSha = "abc123", workflowId = "wfl-subtask-1")
        } else {
          subtask
        }
      },
    )

    val result = DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = durableRuntimeArtifactsJson(durable, subtaskSpec),
      artifactsPatch = mapOf("review_result" to mapOf("finding_count" to 0)),
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-1",
        workflowStatus = "running",
        currentStepId = "audit",
        stepUpdates = listOf(mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    val subtask = result.manifest.subtasks.first()
    assertEquals("abc123", subtask.commitSha)
    assertEquals("wfl-subtask-1", subtask.workflowId)
    assertEquals(mapOf("finding_count" to 0), subtask.reviewResult)
  }

  @Test
  fun `workflow update rejects schema invalid durable decomposition runtime`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-runtime-update")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    assertNotNull(initial)

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestWriter.writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = invalidDurableRuntimeArtifactsJson(initial.manifest),
        artifactsPatch = null,
      )
    }

    assertEquals(DECOMPOSITION_RUNTIME_ARTIFACT_KEY, error.sourceLabel)
    assertContains(error.reason, "contract_version")
    assertContains(error.reason, "offending value: invalid-contract")
  }

  @Test
  fun `workflow projection rejects schema invalid durable decomposition runtime`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-runtime-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    assertNotNull(initial)

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestWriter.writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifactsJson = invalidDurableRuntimeArtifactsJson(initial.manifest),
      )
    }

    assertEquals(DECOMPOSITION_RUNTIME_ARTIFACT_KEY, error.sourceLabel)
    assertContains(error.reason, "contract_version")
    assertContains(error.reason, "offending value: invalid-contract")
  }

  @Test
  fun `execution model can change before any subtask starts`() {
    val repoRoot = Files.createTempDirectory("skillbill-model-change-before-start")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val changed = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = stackedDecompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = null,
        executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
        stackBranches = parseStackBranches(stackedDecompositionPlan(parentSpecPath)),
      ),
    )

    assertNotNull(changed)
    assertEquals("stacked_branches", changed.manifest.executionModel.wireValue)
  }

  @Test
  fun `execution model change after subtask starts fails with manual migration message`() {
    val repoRoot = Files.createTempDirectory("skillbill-model-change-after-start")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    DecompositionManifestWriter.writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = runtimeArtifactsJson(parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")),
      artifactsPatch = null,
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-1",
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1)),
      ),
    )

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestWriter.writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = stackedDecompositionPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = null,
          executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
          stackBranches = parseStackBranches(stackedDecompositionPlan(parentSpecPath)),
        ),
      )
    }

    assertContains(error.reason, "execution_model cannot change after decomposition execution has begun")
    assertContains(error.reason, "manually migrate")
  }

  @Test
  fun `single spec implement plan does not require or write decomposition manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-single-spec")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-single/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val result = DecompositionManifestWriter.writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = mapOf("mode" to "implement", "task_count" to 1),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-single",
      ),
    )

    assertEquals(null, result)
    assertFalse(Files.exists(parentSpecPath.parent.resolve("decomposition-manifest.yaml")))

    val definition = FeatureImplementWorkflowDefinition.definition
    val opened = WorkflowEngine.openRecord(definition, "fis-compat-001", "session-compat", "plan")
    val updated = WorkflowEngine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates =
        listOf(
          mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf("plan" to mapOf("mode" to "implement", "task_count" to 1)),
        sessionId = "session-compat",
      ),
    )

    val payload = WorkflowEngine.fullPayload(definition, updated)
    val artifacts = payload["artifacts"] as Map<*, *>
    assertEquals(mapOf("mode" to "implement", "task_count" to 1), artifacts["plan"])
  }

  @Test
  fun `decomposition planning rejects non exact integer subtask ids before schema validation`() {
    val repoRoot = Files.createTempDirectory("skillbill-decomposition-exact-int")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val fractional = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestWriter.writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanWithFirstSubtaskId(parentSpecPath, 1.5),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    }
    val oversized = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestWriter.writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanWithFirstSubtaskId(parentSpecPath, 4_294_967_297L),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    }

    assertContains(fractional.reason, "id must be an integer")
    assertContains(oversized.reason, "id must be an integer")
  }

  private fun decompositionPlan(
    parentSpecPath: java.nio.file.Path = java.nio.file.Path.of(".feature-specs/SKILL-51-decomposition/spec.md"),
  ): Map<String, Any?> = linkedMapOf(
    "mode" to "decompose",
    "parent_spec_path" to parentSpecPath.toString(),
    "recommended_first_subtask_id" to 1,
    "subtasks" to
      listOf(
        linkedMapOf(
          "id" to 1,
          "name" to "Foundation",
          "spec_path" to parentSpecPath.parent.resolve("spec_subtask_1_foundation.md").toString(),
          "depends_on" to emptyList<Int>(),
          "scope" to "Create contract foundation",
        ),
        linkedMapOf(
          "id" to 2,
          "name" to "Runtime",
          "spec_path" to parentSpecPath.parent.resolve("spec_subtask_2_runtime.md").toString(),
          "depends_on" to listOf(1),
          "scope" to "Wire runtime writer",
        ),
      ),
  )

  private fun stackedDecompositionPlan(
    parentSpecPath: java.nio.file.Path = java.nio.file.Path.of(".feature-specs/SKILL-51-decomposition/spec.md"),
  ): Map<String, Any?> = linkedMapOf(
    "mode" to "decompose",
    "parent_spec_path" to parentSpecPath.toString(),
    "execution_model" to "stacked_branches",
    "recommended_first_subtask_id" to 1,
    "stack_branches" to
      listOf(
        linkedMapOf("subtask_id" to 1, "branch" to "feature/SKILL-51-01-foundation", "base_branch" to "main"),
        linkedMapOf(
          "subtask_id" to 2,
          "branch" to "feature/SKILL-51-02-runtime",
          "base_branch" to "feature/SKILL-51-01-foundation",
        ),
      ),
    "subtasks" to
      listOf(
        linkedMapOf(
          "id" to 1,
          "name" to "Foundation",
          "spec_path" to parentSpecPath.parent.resolve("spec_subtask_1_foundation.md").toString(),
          "depends_on" to emptyList<Int>(),
        ),
        linkedMapOf(
          "id" to 2,
          "name" to "Runtime",
          "spec_path" to parentSpecPath.parent.resolve("spec_subtask_2_runtime.md").toString(),
          "depends_on" to listOf(1),
        ),
      ),
  )

  private fun decompositionPlanWithFirstSubtaskId(
    parentSpecPath: java.nio.file.Path,
    subtaskId: Any,
  ): Map<String, Any?> {
    val plan = LinkedHashMap(decompositionPlan(parentSpecPath))
    val subtasks = (plan.getValue("subtasks") as List<*>).mapIndexed { index, raw ->
      val item = (raw as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
        key.toString() to value
      }
      if (index == 0) {
        item["id"] = subtaskId
      }
      item
    }
    plan["subtasks"] = subtasks
    return plan
  }

  private fun runtimeArtifactsJson(subtaskSpec: java.nio.file.Path): String =
    """{"assessment":{"spec_path":"${subtaskSpec.toString().replace("\\", "\\\\")}"},""" +
      """"branch":{"branch":"feature/SKILL-51-decomposition"}}"""

  private fun durableRuntimeArtifactsJson(
    manifest: skillbill.workflow.model.DecompositionManifest,
    subtaskSpec: java.nio.file.Path,
  ): String = JsonSupport.mapToJsonString(
    mapOf(
      DECOMPOSITION_RUNTIME_ARTIFACT_KEY to manifest.toWireMap(),
      "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
      "branch" to mapOf("branch" to "feature/SKILL-51-decomposition"),
    ),
  )

  private fun invalidDurableRuntimeArtifactsJson(manifest: skillbill.workflow.model.DecompositionManifest): String {
    val invalidManifest = LinkedHashMap(manifest.toWireMap()).apply {
      put("contract_version", "invalid-contract")
    }
    return JsonSupport.mapToJsonString(
      mapOf(
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY to invalidManifest,
      ),
    )
  }
}
