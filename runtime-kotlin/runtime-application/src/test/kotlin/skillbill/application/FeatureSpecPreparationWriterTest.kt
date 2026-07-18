package skillbill.application

import skillbill.application.decomposition.encodeDecompositionManifestYaml
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.featuretask.FeatureSpecPreparationWriter
import skillbill.error.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.SpecSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureSpecPreparationWriterTest {
  private val writer = FeatureSpecPreparationWriter(
    decompositionManifestValidator = testDecompositionManifestValidator,
    fileStore = TestDecompositionManifestFileStore,
  )

  @Test
  fun `single_spec metadata writes parent distinct subtask and authoritative manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-single")
    val result = writer.write(
      repoRoot = repoRoot,
      request = FeatureSpecWriteRequest(
        decision = singleSpecDecision(),
        featureName = "Feature Spec Horizontal Skill",
        parentSpecOverview = "Prepare one spec only.",
        validationStrategy = "bill-code-check",
        subtasks = listOf(singleSubtask()),
      ),
    )

    val parentSpec = repoRoot.resolve(result.parentSpecPath)
    val manifest = parentSpec.parent.resolve("decomposition-manifest.yaml")
    assertEquals(FeatureSpecPreparationMode.SINGLE_SPEC, result.mode)
    assertEquals(result.parentSpecPath, result.featureImplementPath)
    assertTrue(Files.isRegularFile(repoRoot.resolve(result.decompositionManifestPath)))
    assertEquals(1, result.subtaskSpecPaths.size)
    assertTrue(Files.isRegularFile(repoRoot.resolve(result.subtaskSpecPaths.single())))
    assertTrue(Files.isRegularFile(parentSpec))
    assertTrue(Files.exists(manifest))
    assertContains(Files.readString(parentSpec), "## Acceptance Criteria")
    assertTrue("spec_source:" !in Files.readString(manifest))
  }

  @Test
  fun `preparation loud fails when no executable subtask is provided`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-single-subtasks")
    val error = assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      writer.write(
        repoRoot = repoRoot,
        request = FeatureSpecWriteRequest(
          decision = singleSpecDecision(),
          featureName = "feature-spec-horizontal-skill",
          parentSpecOverview = "Every prepared feature needs an executable unit.",
          validationStrategy = "bill-code-check",
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
    val manifest = repoRoot.resolve(result.decompositionManifestPath)
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
  fun `decomposed metadata accepts exactly one ordered subtask`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-decomposed-invalid")
    val result = writer.write(
      repoRoot = repoRoot,
      request = FeatureSpecWriteRequest(
        decision = decomposedDecision(),
        featureName = "feature-spec-horizontal-skill",
        parentSpecOverview = "Invalid decomposition request.",
        validationStrategy = "bill-code-check",
        subtasks = listOf(singleSubtask()),
      ),
    )
    assertEquals(1, result.subtaskSpecPaths.size)
    assertEquals(1, loadDecompositionManifest(repoRoot.resolve(result.decompositionManifestPath)).subtasks.size)
  }

  @Test
  fun `linear preparation stamps source and requires every subtask identity`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-linear")
    val missingIdentity = assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      writer.write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = singleSpecDecision(),
          featureName = "linear-feature",
          parentSpecOverview = "Linear-backed preparation.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask()),
          specSource = SpecSource.LINEAR,
        ),
      )
    }
    assertEquals("subtasks[0].linear_issue_id", missingIdentity.fieldPath)

    val result = writer.write(
      repoRoot,
      FeatureSpecWriteRequest(
        decision = singleSpecDecision(),
        featureName = "linear-feature",
        parentSpecOverview = "Linear-backed preparation.",
        validationStrategy = "bill-code-check",
        subtasks = listOf(singleSubtask().copy(linearIssueId = "linear-subtask-1")),
        specSource = SpecSource.LINEAR,
      ),
    )
    val manifest = loadDecompositionManifest(repoRoot.resolve(result.decompositionManifestPath))
    assertEquals(SpecSource.LINEAR, manifest.specSource)
    assertEquals("linear-subtask-1", manifest.subtasks.single().linearIssueId)
    assertContains(Files.readString(repoRoot.resolve(result.decompositionManifestPath)), "spec_source: \"linear\"")
  }

  @Test
  fun `invalid dependency leaves no partial prepared feature files`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-prevalidate")
    assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      writer.write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = decomposedDecision(),
          featureName = "prevalidated-feature",
          parentSpecOverview = "No partial files.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask().copy(id = 2, dependsOn = listOf(1))),
        ),
      )
    }
    val directory = repoRoot.resolve(".feature-specs/SKILL-59-prevalidated-feature")
    assertTrue(!Files.exists(directory) || Files.list(directory).use { it.findAny().isEmpty })
  }

  @Test
  fun `rewriting prepared artifacts projects preserved manifest runtime status into specs`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-status")
    val request = FeatureSpecWriteRequest(
      decision = singleSpecDecision(),
      featureName = "preserved-status",
      parentSpecOverview = "Preserve runtime authority.",
      validationStrategy = "bill-code-check",
      subtasks = listOf(singleSubtask()),
    )
    val first = writer.write(repoRoot, request)
    val manifestPath = repoRoot.resolve(first.decompositionManifestPath)
    val blocked = loadDecompositionManifest(manifestPath).copy(
      status = "blocked",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
      subtasks = loadDecompositionManifest(manifestPath).subtasks.map { subtask ->
        subtask.copy(status = "blocked", blockedReason = "operator action required")
      },
    )
    TestDecompositionManifestFileStore.writeTextAtomically(
      manifestPath,
      encodeDecompositionManifestYaml(
        blocked,
        testDecompositionManifestValidator,
        TestDecompositionManifestFileStore,
      ),
    )

    val rewritten = writer.write(repoRoot, request)

    assertContains(Files.readString(repoRoot.resolve(rewritten.parentSpecPath)), "status: Blocked")
    assertContains(Files.readString(repoRoot.resolve(rewritten.subtaskSpecPaths.single())), "status: Blocked")
  }

  private fun singleSubtask() = FeatureSpecSubtaskPreparation(
    id = 1,
    name = "implementation",
    scope = "Implement the complete prepared feature.",
    acceptanceCriteria = listOf("The prepared contract is satisfied."),
    nonGoals = emptyList(),
    dependencyNotes = "No dependencies.",
    validationStrategy = "bill-code-check",
    nextPath = "Run bill-feature-task on spec_subtask_1_implementation.md.",
  )

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
