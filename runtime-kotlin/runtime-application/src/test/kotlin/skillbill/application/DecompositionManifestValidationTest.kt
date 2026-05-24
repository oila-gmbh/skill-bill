package skillbill.application

import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStackBranch
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.toWireMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecompositionManifestValidationTest {
  @Test
  fun `valid same branch manifest passes with default execution model`() {
    val manifest = validSameBranchManifest()

    val yaml = encodeDecompositionManifestYaml(manifest)

    val wireMap = manifest.toWireMap()
    assertContains(yaml, "execution_model")
    assertEquals("same_branch_commit_per_subtask", wireMap["execution_model"])
    assertEquals("feature/SKILL-51-decomposition", wireMap["feature_branch"])
    assertEquals(emptyList<Any?>(), wireMap["stack_branches"])
  }

  @Test
  fun `valid stacked branch opt-in passes validation`() {
    val manifest = validSameBranchManifest().copy(
      executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
      featureBranch = null,
      stackBranches =
      listOf(
        DecompositionStackBranch(subtaskId = 1, branch = "feature/SKILL-51-01-foundation", baseBranch = "main"),
        DecompositionStackBranch(
          subtaskId = 2,
          branch = "feature/SKILL-51-02-runtime",
          baseBranch = "feature/SKILL-51-01-foundation",
        ),
      ),
    )

    encodeDecompositionManifestYaml(manifest)

    assertEquals("stacked_branches", manifest.toWireMap()["execution_model"])
  }

  @Test
  fun `malformed schema payload rejects missing required fields`() {
    val wireMap = validSameBranchManifest().toWireMap().toMutableMap()
    wireMap.remove("contract_version")

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      decodeDecompositionManifestMap(wireMap, "missing-contract")
    }
    assertContains(error.reason, "contract_version")
  }

  @Test
  fun `subtask ids above Kotlin Int max fail at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableSubtasks()[0]["id"] = Int.MAX_VALUE.toLong() + 1L

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      decodeDecompositionManifestMap(wireMap, "oversized-subtask-id")
    }
    assertContains(error.reason, "subtasks[0].id")
    assertContains(error.reason, Int.MAX_VALUE.toString())
  }

  @Test
  fun `duplicate subtask ids fail at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableSubtasks()[1]["id"] = 1

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      decodeDecompositionManifestMap(wireMap, "duplicate-subtask-id")
    }
    assertContains(error.reason, "subtasks[1].id")
    assertContains(error.reason, "Duplicate subtask id '1'")
  }

  @Test
  fun `current subtask intent must reference declared subtask at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableCurrentSubtaskIntent()["subtask_id"] = 99

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      decodeDecompositionManifestMap(wireMap, "missing-current-subtask")
    }
    assertContains(error.reason, "current_subtask_intent.subtask_id")
    assertContains(error.reason, "Current subtask intent must reference a declared subtask")
  }

  @Test
  fun `current subtask intent none action must use zero at application decode seam`() {
    val wireMap = validWireMap()
    val intent = wireMap.mutableCurrentSubtaskIntent()
    intent["subtask_id"] = 1
    intent["action"] = "none"

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      decodeDecompositionManifestMap(wireMap, "invalid-none-current-subtask")
    }
    assertContains(error.reason, "current_subtask_intent.subtask_id")
    assertContains(error.reason, "Intent action none must use subtask_id 0")
  }

  @Test
  fun `current subtask intent fractional id fails at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableCurrentSubtaskIntent()["subtask_id"] = 1.5

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      decodeDecompositionManifestMap(wireMap, "fractional-current-subtask")
    }
    assertContains(error.reason, "current_subtask_intent.subtask_id")
  }

  @Test
  fun `same branch manifest rejects missing feature branch at application emission seam`() {
    val manifest = validSameBranchManifest().copy(featureBranch = null)

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      encodeDecompositionManifestYaml(manifest)
    }
    assertContains(error.reason, "feature_branch")
  }

  @Test
  fun `stacked manifest rejects out of order branch declarations at application emission seam`() {
    val manifest = validSameBranchManifest().copy(
      executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
      featureBranch = null,
      stackBranches =
      listOf(
        DecompositionStackBranch(subtaskId = 2, branch = "feature/SKILL-51-02-runtime", baseBranch = "main"),
        DecompositionStackBranch(subtaskId = 1, branch = "feature/SKILL-51-01-foundation", baseBranch = "main"),
      ),
    )

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      encodeDecompositionManifestYaml(manifest)
    }
    assertContains(error.reason, "one branch per subtask in subtask order")
  }

  @Test
  fun `dependency must reference prior subtask at application emission seam`() {
    val manifest = validSameBranchManifest().copy(
      subtasks =
      listOf(
        DecompositionSubtask(
          id = 1,
          name = "Foundation",
          specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
          dependencies = listOf(DecompositionDependency(subtaskId = 2)),
        ),
        DecompositionSubtask(
          id = 2,
          name = "Runtime",
          specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_2_runtime.md",
          dependencies = emptyList(),
        ),
      ),
    )

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      encodeDecompositionManifestYaml(manifest)
    }
    assertContains(error.reason, "earlier declared subtask")
  }

  @Test
  fun `duplicate subtask spec paths fail at application emission seam`() {
    val manifest = validSameBranchManifest().copy(
      subtasks =
      listOf(
        DecompositionSubtask(
          id = 1,
          name = "Foundation",
          specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
          dependencies = emptyList(),
        ),
        DecompositionSubtask(
          id = 2,
          name = "Runtime",
          specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
          dependencies = listOf(DecompositionDependency(subtaskId = 1)),
        ),
      ),
    )

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      encodeDecompositionManifestYaml(manifest)
    }
    assertContains(error.reason, "spec_path")
    assertContains(error.reason, "Duplicate")
  }

  private fun validSameBranchManifest(): DecompositionManifest = DecompositionManifest(
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
        dependencies = emptyList(),
      ),
      DecompositionSubtask(
        id = 2,
        name = "Runtime",
        specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_2_runtime.md",
        dependencies = listOf(DecompositionDependency(subtaskId = 1)),
      ),
    ),
  )

  private fun validWireMap(): MutableMap<String, Any?> = LinkedHashMap(validSameBranchManifest().toWireMap())

  @Suppress("UNCHECKED_CAST")
  private fun MutableMap<String, Any?>.mutableSubtasks(): MutableList<MutableMap<String, Any?>> {
    val mutableSubtasks: MutableList<MutableMap<String, Any?>> = (this["subtasks"] as List<Map<String, Any?>>)
      .mapTo(mutableListOf()) { LinkedHashMap(it) }
    this["subtasks"] = mutableSubtasks
    return mutableSubtasks
  }

  @Suppress("UNCHECKED_CAST")
  private fun MutableMap<String, Any?>.mutableCurrentSubtaskIntent(): MutableMap<String, Any?> {
    val mutableIntent = LinkedHashMap(this["current_subtask_intent"] as Map<String, Any?>)
    this["current_subtask_intent"] = mutableIntent
    return mutableIntent
  }
}
