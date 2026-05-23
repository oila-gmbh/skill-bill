package skillbill.workflow

import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStackBranch
import skillbill.workflow.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecompositionManifestValidationTest {
  @Test
  fun `valid same branch manifest passes with default execution model`() {
    val manifest = validSameBranchManifest()

    DecompositionManifestCodec.validate(manifest)

    val wireMap = manifest.toWireMap()
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

    DecompositionManifestCodec.validate(manifest)

    assertEquals("stacked_branches", manifest.toWireMap()["execution_model"])
  }

  @Test
  fun `malformed schema payload rejects missing required fields`() {
    val wireMap = validSameBranchManifest().toWireMap().toMutableMap()
    wireMap.remove("contract_version")

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestSchemaValidator.validate(wireMap, "missing-contract")
    }
    assertContains(error.reason, "contract_version")
  }

  @Test
  fun `same branch manifest rejects missing feature branch`() {
    val manifest = validSameBranchManifest().copy(featureBranch = null)

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestCodec.validate(manifest)
    }
    assertContains(error.reason, "feature_branch")
  }

  @Test
  fun `stacked manifest rejects out of order branch declarations`() {
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
      DecompositionManifestCodec.validate(manifest)
    }
    assertContains(error.reason, "one branch per subtask in subtask order")
  }

  @Test
  fun `dependency must reference prior subtask`() {
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
      DecompositionManifestCodec.validate(manifest)
    }
    assertContains(error.reason, "earlier declared subtask")
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
}
