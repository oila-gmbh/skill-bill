package skillbill.application

import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.toWireMap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * SKILL-52.3 subtask 1: relocated from the runtime-application
 * `DecompositionManifestWriterTest` because these cases assert REAL schema
 * loud-fails through the decomposition write/projection seams. The concrete
 * schema + coherence validators now live in `runtime-infra-fs`, and
 * `runtime-application` must not depend on infra-fs (enforced by
 * `RuntimeGradleModuleLayeringTest`). runtime-core is the composition layer
 * that legitimately wires the real validator adapter, so the end-to-end
 * loud-fail coverage lives here.
 */
class DecompositionManifestWriterValidationTest {
  private val validator: DecompositionManifestValidator = DecompositionManifestValidatorAdapter()
  private val fileStore = FileSystemDecompositionManifestFileStore()

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
      validator,
      fileStore,
    )
    assertNotNull(initial)

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestWriter.writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = invalidDurableRuntimeArtifactsJson(initial.manifest),
        artifactsPatch = null,
        validator = validator,
        fileStore = fileStore,
      )
    }

    assertEquals("decomposition_runtime", error.sourceLabel)
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
      validator,
      fileStore,
    )
    assertNotNull(initial)

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestWriter.writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifactsJson = invalidDurableRuntimeArtifactsJson(initial.manifest),
        validator = validator,
        fileStore = fileStore,
      )
    }

    assertEquals("decomposition_runtime", error.sourceLabel)
    assertContains(error.reason, "contract_version")
    assertContains(error.reason, "offending value: invalid-contract")
  }

  private fun decompositionPlan(parentSpecPath: Path): Map<String, Any?> = linkedMapOf(
    "mode" to "decompose",
    "parent_spec_path" to parentSpecPath.toString(),
    "recommended_first_subtask_id" to 1,
    "subtasks" to listOf(
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

  private fun invalidDurableRuntimeArtifactsJson(manifest: DecompositionManifest): String {
    val invalidManifest = LinkedHashMap(manifest.toWireMap()).apply {
      put("contract_version", "invalid-contract")
    }
    return JsonSupport.mapToJsonString(mapOf("decomposition_runtime" to invalidManifest))
  }
}
