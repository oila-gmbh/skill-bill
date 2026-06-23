package skillbill.workflow

import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.SpecSource
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
  fun `codec round-trips spec_source and per-subtask linear_issue_id`() {
    val manifest = validManifest().copy(
      specSource = SpecSource.LINEAR,
      subtasks = listOf(
        DecompositionSubtask(
          id = 1,
          name = "Foundation",
          specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
          linearIssueId = "SKILL-512",
        ),
      ),
    )

    val decoded = DecompositionManifestCodec.decodeMap(manifest.toWireMap())

    assertEquals(manifest, decoded)
    assertEquals(SpecSource.LINEAR, decoded.specSource)
    assertEquals("SKILL-512", decoded.subtasks.single().linearIssueId)
  }

  @Test
  fun `codec defaults absent spec_source to local`() {
    val wireMap = validManifest().toWireMap().toMutableMap()
    wireMap.remove("spec_source")

    val decoded = DecompositionManifestCodec.decodeMap(wireMap)

    assertEquals(SpecSource.LOCAL, decoded.specSource)
  }

  @Test
  fun `codec loads a 0_2-era manifest without spec_source, preserving the version and defaulting local`() {
    // SKILL-71 back-compatibility sweep: a manifest written before the spec_source contract bump
    // carries contract_version "0.2" and no spec_source. The load path must still decode it,
    // preserve the recorded version verbatim (no silent rewrite), and resolve spec_source to local.
    val wireMap = validManifest().toWireMap().toMutableMap()
    wireMap["contract_version"] = "0.2"
    wireMap.remove("spec_source")

    val decoded = DecompositionManifestCodec.decodeMap(wireMap, "0.2-era-manifest")

    assertEquals("0.2", decoded.contractVersion)
    assertEquals(SpecSource.LOCAL, decoded.specSource)
  }

  @Test
  fun `codec rejects unsupported spec_source value`() {
    val wireMap = validManifest().toWireMap().toMutableMap()
    wireMap["spec_source"] = "github"

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestCodec.decodeMap(wireMap, "codec-spec-source")
    }
    assertContains(error.reason, "spec_source 'github' is not supported")
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
  fun `codec round-trips per-subtask agent attribution`() {
    val manifest = validManifest().copy(
      subtasks = listOf(
        DecompositionSubtask(
          id = 1,
          name = "Foundation",
          specPath = ".feature-specs/SKILL-89-attribution/spec_subtask_1_foundation.md",
          status = "complete",
          finalizingAgentId = "claude",
          participatingAgentIds = listOf("codex", "claude"),
        ),
      ),
    )

    val decoded = DecompositionManifestCodec.decodeMap(manifest.toWireMap())

    assertEquals(manifest, decoded)
    assertEquals("claude", decoded.subtasks.single().finalizingAgentId)
    assertEquals(listOf("codex", "claude"), decoded.subtasks.single().participatingAgentIds)
  }

  @Test
  fun `codec loads a legacy manifest without agent attribution fields, defaulting to null and empty`() {
    // A decomposition-manifest.yaml written before SKILL-89 omits both agent keys entirely.
    val wireMap = validManifest().toWireMap().toMutableMap()

    @Suppress("UNCHECKED_CAST")
    val subtasks = (wireMap["subtasks"] as List<Map<String, Any?>>).map { subtask ->
      subtask.toMutableMap().apply {
        remove("finalizing_agent_id")
        remove("participating_agent_ids")
      }
    }
    wireMap["subtasks"] = subtasks

    val decoded = DecompositionManifestCodec.decodeMap(wireMap)

    val subtask = decoded.subtasks.single()
    assertEquals(null, subtask.finalizingAgentId)
    assertEquals(emptyList(), subtask.participatingAgentIds)
    // The legacy manifest round-trips cleanly (re-encode then decode is stable).
    assertEquals(decoded, DecompositionManifestCodec.decodeMap(decoded.toWireMap()))
  }

  @Test
  fun `codec rejects a non-string participating agent element`() {
    val wireMap = validManifest().toWireMap().toMutableMap()

    @Suppress("UNCHECKED_CAST")
    val subtasks = (wireMap["subtasks"] as List<Map<String, Any?>>).map { subtask ->
      subtask.toMutableMap().apply { put("participating_agent_ids", listOf("codex", 7)) }
    }
    wireMap["subtasks"] = subtasks

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestCodec.decodeMap(wireMap, "codec-agent-attribution")
    }
    assertContains(error.reason, "participating_agent_ids must be a list of strings")
  }

  @Test
  fun `codec rejects a blank-string element in participating_agent_ids`() {
    val wireMap = validManifest().toWireMap().toMutableMap()

    @Suppress("UNCHECKED_CAST")
    val subtasks = (wireMap["subtasks"] as List<Map<String, Any?>>).map { subtask ->
      subtask.toMutableMap().apply { put("participating_agent_ids", listOf("codex", "")) }
    }
    wireMap["subtasks"] = subtasks

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestCodec.decodeMap(wireMap, "codec-blank-agent-element")
    }
    assertContains(error.reason, "participating_agent_ids must be a list of non-blank strings")
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
