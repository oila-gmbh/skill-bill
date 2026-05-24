package skillbill.application

import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.error.InvalidDecompositionManifestSchemaError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class DecompositionManifestWriterSelectorTest {
  @Test
  fun `decomposition planning rejects present invalid selector ids`() {
    val repoRoot = Files.createTempDirectory("skillbill-decomposition-selector-int")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val invalidCurrent = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlan(parentSpecPath).withPlanningValue("current_subtask_id", 1.5),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    }
    val invalidRecommended = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlan(parentSpecPath).withPlanningValue("recommended_first_subtask_id", 1.5),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    }

    assertContains(invalidCurrent.reason, "current_subtask_id must be an integer")
    assertContains(invalidRecommended.reason, "recommended_first_subtask_id must be an integer")
  }

  private fun decompositionPlan(parentSpecPath: Path): Map<String, Any?> = linkedMapOf(
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

  private fun Map<String, Any?>.withPlanningValue(key: String, value: Any?): Map<String, Any?> =
    LinkedHashMap(this).apply {
      put(key, value)
    }
}
