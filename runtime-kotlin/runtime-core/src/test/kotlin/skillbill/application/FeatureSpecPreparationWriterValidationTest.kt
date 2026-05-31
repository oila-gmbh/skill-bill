package skillbill.application

import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.model.RuntimeContext
import skillbill.workflow.model.DecompositionManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureSpecPreparationWriterValidationTest {
  private val validator = DecompositionManifestValidatorAdapter()
  private val fileStore = FileSystemDecompositionManifestFileStore()
  private val writer = FeatureSpecPreparationWriter(validator, fileStore)

  @Test
  fun `decomposed preparation writes schema valid manifest and is goal readable`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-goal-readable")
    val dbPath = repoRoot.resolve("metrics.db")
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          dbPathOverride = dbPath.toString(),
          environment = emptyMap(),
          userHome = repoRoot,
        ),
      )

    val result = writer.write(repoRoot = repoRoot, request = decomposedWriteRequest())

    val manifestPath = repoRoot.resolve(result.decompositionManifestPath!!)
    assertTrue(Files.isRegularFile(manifestPath))
    assertSubtaskSpecsAreRunnable(repoRoot, result)
    val manifest = loadDecompositionManifest(manifestPath, fileStore, validator)
    assertManifestSupportsGoalRunner(repoRoot, result, manifest)

    val goalStatus = component.goalRunnerStatusService.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-59",
        invokedAgentId = "codex",
        dbPathOverride = dbPath.toString(),
        repoRoot = repoRoot,
      ),
    )
    assertNotNull(goalStatus)
    assertEquals("SKILL-59", goalStatus.issueKey)
    assertEquals(0, goalStatus.completeCount)
    assertEquals(2, goalStatus.pendingCount)
    assertEquals(0, goalStatus.blockedCount)
    assertEquals(1, goalStatus.currentSubtaskId)
  }

  private fun decomposedWriteRequest(): FeatureSpecWriteRequest = FeatureSpecWriteRequest(
    decision = FeatureSpecPreparationDecision(
      issueKey = "SKILL-59",
      intendedOutcome = "decomposed",
      acceptanceCriteria = listOf("Write parent and subtask specs."),
      constraints = listOf("Reuse decomposition writer/validator seams."),
      nonGoals = listOf("Do not run implementation."),
      mode = FeatureSpecPreparationMode.DECOMPOSED,
    ),
    featureName = "feature-spec-horizontal-skill",
    parentSpecOverview = "Prepare runtime-owned decomposition artifacts.",
    validationStrategy = "bill-quality-check",
    subtasks = listOf(
      FeatureSpecSubtaskPreparation(
        id = 1,
        name = "foundation",
        scope = "Prepare typed write contracts.",
        acceptanceCriteria = listOf("Shared writer models exist."),
        nonGoals = listOf("No skill wiring."),
        dependencyNotes = "No dependencies.",
        validationStrategy = "bill-quality-check",
        nextPath = "Run bill-feature-implement on spec_subtask_1_foundation.md.",
        dependsOn = emptyList(),
      ),
      FeatureSpecSubtaskPreparation(
        id = 2,
        name = "runtime",
        scope = "Write specs and schema-valid manifest.",
        acceptanceCriteria = listOf("Manifest loads through goal status import."),
        nonGoals = listOf("No final integration wiring."),
        dependencyNotes = "Depends on subtask 1 contracts.",
        validationStrategy = "bill-quality-check",
        nextPath = "Run bill-feature-implement on spec_subtask_2_runtime.md.",
        dependsOn = listOf(1),
      ),
    ),
  )

  private fun assertSubtaskSpecsAreRunnable(repoRoot: Path, result: FeatureSpecWriteResult) {
    assertEquals(2, result.subtaskSpecPaths.size)
    result.subtaskSpecPaths.forEach { subtaskSpecPath ->
      val fullPath = repoRoot.resolve(subtaskSpecPath)
      assertTrue(Files.isRegularFile(fullPath))
      assertContains(Files.readString(fullPath), "Run bill-feature-implement on")
    }
  }

  private fun assertManifestSupportsGoalRunner(
    repoRoot: Path,
    result: FeatureSpecWriteResult,
    manifest: DecompositionManifest,
  ) {
    assertEquals("SKILL-59", manifest.issueKey)
    assertEquals(2, manifest.subtasks.size)
    assertEquals(1, manifest.currentSubtaskIntent.subtaskId)
    assertEquals("start", manifest.currentSubtaskIntent.action)
    assertEquals(
      result.subtaskSpecPaths.map { repoRoot.resolve(it).normalize().toString() },
      manifest.subtasks.map { it.specPath },
    )
  }
}
