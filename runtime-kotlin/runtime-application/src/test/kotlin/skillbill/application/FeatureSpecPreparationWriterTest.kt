package skillbill.application

import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.featuretask.FeatureSpecPreparationWriter
import skillbill.application.workflow.repoRoot
import skillbill.error.FeatureSpecPreparationModeConflictError
import skillbill.error.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureSpecPreparationWriterTest {
  private val writer = FeatureSpecPreparationWriter(
    decompositionManifestValidator = testDecompositionManifestValidator,
    fileStore = TestDecompositionManifestFileStore,
  )

  @Test
  fun `single_spec writes parent spec and reports feature implement path without manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-single")
    val result = writer.write(
      repoRoot = repoRoot,
      request = FeatureSpecWriteRequest(
        decision = singleSpecDecision(),
        featureName = "Feature Spec Horizontal Skill",
        parentSpecOverview = "Prepare one spec only.",
        validationStrategy = "bill-code-check",
      ),
    )

    val parentSpec = repoRoot.resolve(result.parentSpecPath)
    val manifest = parentSpec.parent.resolve("decomposition-manifest.yaml")
    assertEquals(FeatureSpecPreparationMode.SINGLE_SPEC, result.mode)
    assertEquals(result.parentSpecPath, result.featureImplementPath)
    assertEquals(null, result.decompositionManifestPath)
    assertTrue(Files.isRegularFile(parentSpec))
    assertFalse(Files.exists(manifest))
    assertContains(Files.readString(parentSpec), "## Acceptance Criteria")
  }

  @Test
  fun `single_spec loud fails when decomposition manifest already exists`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-conflict")
    val specDir = repoRoot.resolve(".feature-specs/SKILL-59-feature-spec-horizontal-skill")
    Files.createDirectories(specDir)
    Files.writeString(specDir.resolve("decomposition-manifest.yaml"), "contract_version: \"0.2\"\n")

    val error = assertFailsWith<FeatureSpecPreparationModeConflictError> {
      writer.write(
        repoRoot = repoRoot,
        request = FeatureSpecWriteRequest(
          decision = singleSpecDecision(),
          featureName = "feature-spec-horizontal-skill",
          parentSpecOverview = "Should fail because manifest exists.",
          validationStrategy = "bill-code-check",
        ),
      )
    }

    assertContains(error.reason, "single_spec cannot run beside an existing decomposition manifest")
  }

  @Test
  fun `single_spec loud fails when decomposition subtasks are provided`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-single-subtasks")
    val error = assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      writer.write(
        repoRoot = repoRoot,
        request = FeatureSpecWriteRequest(
          decision = singleSpecDecision(),
          featureName = "feature-spec-horizontal-skill",
          parentSpecOverview = "single_spec should reject decomposed-only payload fields.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(
            FeatureSpecSubtaskPreparation(
              id = 1,
              name = "decomposed-only",
              scope = "Should not be accepted in single_spec mode.",
              acceptanceCriteria = listOf("Rejected."),
              nonGoals = emptyList(),
              dependencyNotes = "",
              validationStrategy = "bill-code-check",
              nextPath = "Run bill-feature-task on spec_subtask_1_decomposed-only.md.",
            ),
          ),
        ),
      )
    }

    assertEquals("subtasks", error.fieldPath)
  }

  @Test
  fun `decomposed writes parent and ordered subtask specs then writes manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-decomposed")
    val result = writer.write(
      repoRoot = repoRoot,
      request = FeatureSpecWriteRequest(
        decision = decomposedDecision(),
        featureName = "feature-spec-horizontal-skill",
        parentSpecOverview = "Prepare decomposition artifacts.",
        validationStrategy = "bill-code-check",
        subtasks = listOf(
          FeatureSpecSubtaskPreparation(
            id = 1,
            name = "foundation",
            scope = "Build shared runtime write request/result contracts.",
            acceptanceCriteria = listOf("Contracts are reusable by implement and goal."),
            nonGoals = listOf("Do not wire skills yet."),
            dependencyNotes = "Runs first and has no dependencies.",
            validationStrategy = "bill-code-check",
            nextPath = "Run bill-feature-task on spec_subtask_1_foundation.md.",
            dependsOn = emptyList(),
          ),
          FeatureSpecSubtaskPreparation(
            id = 2,
            name = "runtime-writer",
            scope = "Write parent/subtask specs and decomposition manifest.",
            acceptanceCriteria = listOf("Manifest validates and can be consumed by goal."),
            nonGoals = listOf("Do not add feature-spec skill wiring yet."),
            dependencyNotes = "Depends on the shared preparation contracts from subtask 1.",
            validationStrategy = "bill-code-check",
            nextPath = "Run bill-feature-task on spec_subtask_2_runtime-writer.md.",
            dependsOn = listOf(1),
          ),
        ),
      ),
    )

    val parentSpec = repoRoot.resolve(result.parentSpecPath)
    val manifest = repoRoot.resolve(result.decompositionManifestPath!!)
    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, result.mode)
    assertTrue(Files.isRegularFile(parentSpec))
    assertTrue(Files.isRegularFile(manifest))
    assertEquals(2, result.subtaskSpecPaths.size)
    result.subtaskSpecPaths.forEach { subtaskPath ->
      val subtaskSpec = repoRoot.resolve(subtaskPath)
      val text = Files.readString(subtaskSpec)
      assertContains(text, "## Scope")
      assertContains(text, "## Acceptance Criteria")
      assertContains(text, "## Non-Goals")
      assertContains(text, "## Dependency Notes")
      assertContains(text, "## Validation Strategy")
      assertContains(text, "## Next Path")
    }
    val loadedManifest = loadDecompositionManifest(manifest)
    assertEquals("SKILL-59", loadedManifest.issueKey)
    assertEquals(2, loadedManifest.subtasks.size)
  }

  @Test
  fun `decomposed requires at least two ordered subtasks`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-decomposed-invalid")
    val error = assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      writer.write(
        repoRoot = repoRoot,
        request = FeatureSpecWriteRequest(
          decision = decomposedDecision(),
          featureName = "feature-spec-horizontal-skill",
          parentSpecOverview = "Invalid decomposition request.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(
            FeatureSpecSubtaskPreparation(
              id = 1,
              name = "only",
              scope = "Only one subtask",
              acceptanceCriteria = listOf("Too small"),
              nonGoals = emptyList(),
              dependencyNotes = "",
              validationStrategy = "bill-code-check",
              nextPath = "Run bill-feature-task on spec_subtask_1_only.md.",
            ),
          ),
        ),
      )
    }

    assertEquals("subtasks", error.fieldPath)
  }

  private fun singleSpecDecision(): FeatureSpecPreparationDecision = FeatureSpecPreparationDecision(
    issueKey = "SKILL-59",
    intendedOutcome = "single_spec",
    acceptanceCriteria = listOf("Write parent spec."),
    constraints = listOf("No decomposition manifest for single-spec mode."),
    nonGoals = listOf("Do not create subtasks."),
    mode = FeatureSpecPreparationMode.SINGLE_SPEC,
  )

  private fun decomposedDecision(): FeatureSpecPreparationDecision = FeatureSpecPreparationDecision(
    issueKey = "SKILL-59",
    intendedOutcome = "decomposed",
    acceptanceCriteria = listOf("Write parent spec and decomposition artifacts."),
    constraints = listOf("Reuse manifest writer."),
    nonGoals = listOf("No skill wiring in this subtask."),
    mode = FeatureSpecPreparationMode.DECOMPOSED,
  )
}
