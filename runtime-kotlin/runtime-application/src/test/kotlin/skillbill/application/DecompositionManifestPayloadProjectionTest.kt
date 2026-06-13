package skillbill.application

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.toWireMap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DecompositionManifestPayloadProjectionTest {
  @Test
  fun `decomposition runtime omits review audit and validation result payloads`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-omits-result-payloads")
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
      artifactsPatch = mapOf(
        "review_result" to mapOf("finding_count" to 0),
        "audit_report" to mapOf("pass" to true),
        "validation_result" to mapOf("passed" to true),
      ),
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-1",
        workflowStatus = "running",
        currentStepId = "validate",
        stepUpdates = listOf(mapOf("step_id" to "validate", "status" to "completed", "attempt_count" to 1)),
      ),
    )

    assertNotNull(result)
    val subtaskWire = firstSubtaskWire(result.manifest)
    assertFalse("review_result" in subtaskWire)
    assertFalse("audit_result" in subtaskWire)
    assertFalse("validation_result" in subtaskWire)
  }

  private fun firstSubtaskWire(manifest: DecompositionManifest): Map<*, *> =
    (manifest.toWireMap().getValue("subtasks") as List<*>).first() as Map<*, *>

  private fun decompositionPlan(parentSpecPath: Path, subtaskSpec: Path): Map<String, Any?> = linkedMapOf(
    "mode" to "decompose",
    "parent_spec_path" to parentSpecPath.toString(),
    "recommended_first_subtask_id" to 1,
    "subtasks" to listOf(
      linkedMapOf(
        "id" to 1,
        "name" to "Foundation",
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
        "branch" to mapOf("branch" to "feature/SKILL-51-decomposition"),
      ),
    )
}
