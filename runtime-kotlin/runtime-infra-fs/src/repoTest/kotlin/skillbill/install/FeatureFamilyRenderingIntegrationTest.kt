package skillbill.install

import skillbill.install.staging.stageInstalledSkill
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureFamilyRenderingIntegrationTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir)) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `governed feature family stages merged feature contracts without source mutation`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-feature-family-staging").also(tempDirs::add)
    val sourceFilesBefore = reviewSourceFiles(repoRoot).associateWith(Files::readAllBytes)

    val staged = stageInstalledSkill(repoRoot, repoRoot.resolve("skills/bill-feature"), home)
    val stagedReview = stageInstalledSkill(repoRoot, repoRoot.resolve("skills/bill-code-review"), home)

    val feature = staged.renderedSkillFile.readText()
    val goal = staged.stagingDir.resolve("bill-feature-goal.md").readText()

    assertContains(feature, "When omitted, do not synthesize a `code-review:` token; preserve")
    assertContains(feature, "omitting the `code-review:` token when the caller did not provide it")
    assertContains(feature, "canonical manifest source identity, content digest, and confirmation description")
    assertContains(goal, "forward it unchanged to every runtime\nchild and child continuation artifact")
    assertContains(goal, "After review, `verify_findings` verifies each finding against the subtask spec intent")
    assertContains(goal, "at most one bounded `implement_fix` round for every verified finding regardless of severity")
    assertContains(goal, "then the child advances to `validate` even when verified findings remain unfixed")
    assertContains(
      goal,
      "Rejected findings are recorded in the goal-wide unaddressed-findings ledger and are never fixed",
    )
    assertFalse(feature.contains("bill-feature-task"))
    assertFalse(feature.contains("bill-feature-task-runtime"))
    assertFalse(Files.exists(staged.stagingDir.resolve("bill-feature-task.md")))
    assertFalse(Files.exists(staged.stagingDir.resolve("bill-feature-task-runtime.md")))
    listOf(
      "peak-hours-warner.md",
      "shell-ceremony.md",
      "telemetry-contract.md",
      "android-compose-implementation.md",
      "android-navigation-implementation.md",
      "android-interop-implementation.md",
      "android-design-system-implementation.md",
      "android-r8-implementation.md",
      "android-compose-edge-to-edge.md",
      "android-compose-adaptive-layouts.md",
    ).forEach { pointer ->
      assertTrue(Files.isRegularFile(staged.stagingDir.resolve(pointer)), pointer)
    }
    assertContains(stagedReview.renderedSkillFile.readText(), "mode:auto|inline|delegated")
    assertFalse(stagedReview.renderedSkillFile.readText().contains("execution-mode:auto|inline|delegated"))
    assertTrue(sourceFilesBefore.all { (path, bytes) -> bytes.contentEquals(Files.readAllBytes(path)) })
  }

  private fun reviewSourceFiles(repoRoot: Path): List<Path> = Files.walk(repoRoot.resolve("skills")).use { paths ->
    paths
      .filter(Files::isRegularFile)
      .filter { path ->
        path.parent.toString().contains("bill-feature") || path.parent.toString().contains("bill-code-review")
      }
      .toList()
  }
}
