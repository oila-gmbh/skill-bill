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
  fun `governed feature family stages review mode contracts without source mutation`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-feature-family-staging").also(tempDirs::add)
    val sourceFilesBefore = reviewSourceFiles(repoRoot).associateWith(Files::readAllBytes)

    val staged = stageInstalledSkill(repoRoot, repoRoot.resolve("skills/bill-feature"), home)
    val stagedReview = stageInstalledSkill(repoRoot, repoRoot.resolve("skills/bill-code-review"), home)

    val feature = staged.renderedSkillFile.readText()
    val task = staged.stagingDir.resolve("bill-feature-task.md").readText()
    val goal = staged.stagingDir.resolve("bill-feature-goal.md").readText()
    val runtime = staged.stagingDir.resolve("bill-feature-task-runtime.md").readText()

    assertFalse(Files.exists(staged.stagingDir.resolve("bill-feature-task-prose.md")))
    assertFalse(Files.exists(staged.stagingDir.resolve("bill-feature-task-subtask-runner.md")))

    assertContains(feature, "When omitted, do not synthesize a `code-review:` token; preserve")
    assertContains(feature, "omitting the `code-review:` token when the caller did not provide it")
    assertContains(feature, "canonical manifest source identity, content digest, and confirmation description")
    assertContains(task, "Omission resolves to `inline`")
    assertContains(task, "Forward the resolved selection unchanged to the runtime sidecar")
    assertContains(task, "selected agent add-on slugs and manifest descriptions in caller order, or `none`")
    assertContains(goal, "forward it unchanged to every runtime\nchild and child continuation artifact")
    assertContains(goal, "Major, Minor,\nand Nit findings remain durable evidence and never prevent advancement")
    assertContains(goal, "no finite count pauses or advances the run")
    assertContains(runtime, "--code-review-mode <auto|inline|delegated>")
    assertContains(runtime, "--agent-addon-selection-json <structured-json>")
    assertContains(runtime, "Do not parse, reorder, or rediscover it")
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
