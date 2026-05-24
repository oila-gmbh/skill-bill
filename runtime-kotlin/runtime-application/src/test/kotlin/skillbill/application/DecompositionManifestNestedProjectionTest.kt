package skillbill.application

import skillbill.contracts.JsonSupport
import skillbill.workflow.toWireMap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DecompositionManifestNestedProjectionTest {
  @Test
  fun `nested decomposition writes manifest beside child specs without overwriting parent manifest`() {
    val fixture = nestedProjectionFixture()

    val result = writeFromWorkflowUpdate(
      repoRoot = fixture.repoRoot,
      existingArtifactsJson = "{}",
      artifactsPatch = mapOf("plan" to nestedDecompositionPlan(fixture.parentSpecPath, fixture.nestedDirectory)),
    )

    assertNotNull(result)
    assertEquals(fixture.nestedManifestPath, result.manifestPath)
    assertEquals("parent-manifest", Files.readString(fixture.parentManifestPath))
    assertEquals("SKILL-52.1", result.manifest.issueKey)
    assertEquals("install-policy-extraction", result.manifest.featureName)
  }

  @Test
  fun `runtime projection keeps nested manifest beside child specs`() {
    val fixture = nestedProjectionFixture()
    val initial = writeFromWorkflowUpdate(
      repoRoot = fixture.repoRoot,
      existingArtifactsJson = "{}",
      artifactsPatch = mapOf("plan" to nestedDecompositionPlan(fixture.parentSpecPath, fixture.nestedDirectory)),
    )
    assertNotNull(initial)
    val completed = initial.manifest.copy(
      subtasks = initial.manifest.subtasks.map { subtask ->
        if (subtask.id == 1) subtask.copy(status = "complete", workflowId = "wfl-child") else subtask
      },
    )

    val result = writeProjectionFromWorkflowState(
      repoRoot = fixture.repoRoot,
      artifactsJson = JsonSupport.mapToJsonString(mapOf(DECOMPOSITION_RUNTIME_ARTIFACT_KEY to completed.toWireMap())),
    )

    assertNotNull(result)
    assertEquals(fixture.nestedManifestPath, result.manifestPath)
    assertEquals("parent-manifest", Files.readString(fixture.parentManifestPath))
    assertEquals("complete", loadDecompositionManifest(result.manifestPath).subtasks.single { it.id == 1 }.status)
  }
}

private data class NestedProjectionFixture(
  val repoRoot: Path,
  val parentSpecPath: Path,
  val nestedDirectory: Path,
  val parentManifestPath: Path,
  val nestedManifestPath: Path,
)

private fun nestedProjectionFixture(): NestedProjectionFixture {
  val repoRoot = Files.createTempDirectory("skillbill-nested-decomposition-manifest")
  val parentSpecPath =
    repoRoot.resolve(".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_3_install-policy.md")
  val nestedDirectory = parentSpecPath.parent.resolve("install-policy-extraction")
  val childSpecPath = nestedDirectory.resolve("spec_subtask_1_foundation.md")
  Files.createDirectories(nestedDirectory)
  val parentManifestPath = parentSpecPath.parent.resolve("decomposition-manifest.yaml")
  Files.writeString(parentManifestPath, "parent-manifest")
  Files.writeString(parentSpecPath, "# Parent subtask spec\n")
  Files.writeString(childSpecPath, "# Nested child spec\n")
  return NestedProjectionFixture(
    repoRoot = repoRoot,
    parentSpecPath = parentSpecPath,
    nestedDirectory = nestedDirectory,
    parentManifestPath = parentManifestPath,
    nestedManifestPath = nestedDirectory.resolve("decomposition-manifest.yaml"),
  )
}

private fun nestedDecompositionPlan(parentSpecPath: Path, subtaskDirectory: Path): Map<String, Any?> = linkedMapOf(
  "mode" to "decompose",
  "parent_spec_path" to parentSpecPath.toString(),
  "recommended_first_subtask_id" to 1,
  "subtasks" to listOf(
    linkedMapOf(
      "id" to 1,
      "name" to "Foundation",
      "spec_path" to subtaskDirectory.resolve("spec_subtask_1_foundation.md").toString(),
      "depends_on" to emptyList<Int>(),
    ),
    linkedMapOf(
      "id" to 2,
      "name" to "Runtime",
      "spec_path" to subtaskDirectory.resolve("spec_subtask_2_runtime.md").toString(),
      "depends_on" to listOf(1),
    ),
  ),
)
