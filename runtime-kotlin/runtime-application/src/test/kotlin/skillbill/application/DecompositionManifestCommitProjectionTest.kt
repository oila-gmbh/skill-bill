package skillbill.application

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.contracts.JsonSupport
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.toWireMap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DecompositionManifestCommitProjectionTest {
  @Test
  fun `runtime update does not project commit push sha into decomposition manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-commit-sha-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial = writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlan(parentSpecPath, subtaskSpec),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    assertNotNull(initial)

    val result = writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = durableRuntimeArtifactsJson(initial.manifest, subtaskSpec),
      artifactsPatch = mapOf("commit_push_result" to mapOf("commit_sha" to "commit-subtask-1")),
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-1",
        workflowStatus = "completed",
        currentStepId = "finish",
        stepUpdates = listOf(mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    val subtask = result.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", subtask.status)
    assertEquals(null, subtask.commitSha)
  }

  private fun decompositionPlan(parentSpecPath: Path, subtaskSpec: Path): Map<String, Any?> = mapOf(
    "mode" to "decompose",
    "issue_key" to "SKILL-51",
    "feature_name" to "decomposition",
    "parent_spec_path" to parentSpecPath.toString(),
    "execution_model" to "same_branch_commit_per_subtask",
    "subtasks" to listOf(
      mapOf(
        "id" to 1,
        "name" to "foundation",
        "spec_path" to subtaskSpec.toString(),
        "depends_on" to emptyList<Int>(),
      ),
    ),
  )

  private fun durableRuntimeArtifactsJson(manifest: DecompositionManifest, subtaskSpec: Path): String =
    JsonSupport.mapToJsonString(
      mapOf(
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY to manifest.toWireMap(),
        "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
      ),
    )
}
