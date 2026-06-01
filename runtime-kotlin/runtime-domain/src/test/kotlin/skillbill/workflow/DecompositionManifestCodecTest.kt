package skillbill.workflow

import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecompositionManifestCodecTest {
  @Test
  fun `codec decodes manifest wire values without schema validation`() {
    val manifest = validManifest().copy(featureBranch = null)

    val decoded = DecompositionManifestCodec.decodeMap(manifest.toWireMap())

    assertEquals(manifest.copy(featureBranch = null), decoded)
  }

  @Test
  fun `codec reports local type mapping failures`() {
    val wireMap = validManifest().toWireMap().toMutableMap()
    wireMap["issue_key"] = 42

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestCodec.decodeMap(wireMap, "codec-type-mapping")
    }
    assertContains(error.reason, "issue_key must be a string")
  }

  @Test
  fun `manifest exposes next sibling subtask id without schema changes`() {
    val manifest = validManifest().copy(
      subtasks = listOf(
        DecompositionSubtask(
          id = 1,
          name = "Foundation",
          specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
        ),
        DecompositionSubtask(
          id = 3,
          name = "Follow up",
          specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_3_follow_up.md",
        ),
      ),
    )

    assertEquals(4, manifest.nextSubtaskId())
    assertEquals(manifest, DecompositionManifestCodec.decodeMap(manifest.toWireMap()))
  }

  private fun validManifest(): DecompositionManifest = DecompositionManifest(
    issueKey = "SKILL-51",
    featureName = "decomposition",
    parentSpecPath = ".feature-specs/SKILL-51-decomposition/spec.md",
    baseBranch = "main",
    featureBranch = "feature/SKILL-51-decomposition",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
    subtasks =
    listOf(
      DecompositionSubtask(
        id = 1,
        name = "Foundation",
        specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
      ),
    ),
  )
}
