package skillbill.install

import skillbill.install.staging.stageInstalledSkill
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.nativeagent.rendering.NativeAgentProvider
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
    val prose = staged.stagingDir.resolve("bill-feature-task-prose.md").readText()
    val runtime = staged.stagingDir.resolve("bill-feature-task-runtime.md").readText()
    val subtaskRunner = staged.stagingDir.resolve("bill-feature-task-subtask-runner.md").readText()
    val nativeAgents = repoRoot.resolve("skills/bill-feature-task-prose/native-agents/agents.yaml").readText()
    val renderedNativeAgents = parseNativeAgentBundle(
      repoRoot.resolve("skills/bill-feature-task-prose/native-agents/agents.yaml"),
    ).filter { source -> source.name == "bill-feature-task-subtask-runner" }.flatMap { source ->
      NativeAgentProvider.entries.map { provider ->
        val output = home.resolve("native-agents/${provider.directoryName}/${source.name}.md")
        Files.createDirectories(output.parent)
        Files.writeString(output, provider.render(source))
        output.readText()
      }
    }

    assertContains(feature, "When omitted, do not synthesize `code-review:auto`; preserve")
    assertContains(feature, "omitting the `code-review:` token when the caller did not provide it")
    assertContains(task, "Omission resolves to `auto`")
    assertContains(task, "Forward the resolved selection unchanged to either sidecar")
    assertContains(goal, "For a prose-goal resume, an omitted `code-review:`")
    assertContains(goal, "without claiming approval or launching a third pass")
    assertContains(prose, "bill-code-review execution-mode:<selected-mode>")
    assertContains(runtime, "--code-review-mode <auto|inline|delegated>")
    assertContains(stagedReview.renderedSkillFile.readText(), "execution-mode:auto|inline|delegated")
    assertContains(subtaskRunner, "bill-code-review execution-mode:<code_review_mode>")
    assertFalse(subtaskRunner.contains("bill-code-review execution-mode:code_review_mode"))
    assertContains(nativeAgents, "Completed review passes: {completed_review_pass_count}")
    assertContains(nativeAgents, "Reserved review pass: {reserved_review_pass_number}")
    assertContains(nativeAgents, "from `{review_base_sha}`")
    assertContains(nativeAgents, "Never start pass three")
    assertTrue(renderedNativeAgents.isNotEmpty())
    renderedNativeAgents.forEach { rendered ->
      assertContains(rendered, "Completed review passes: {completed_review_pass_count}")
      assertContains(rendered, "Never start pass three")
      assertContains(rendered, "never include a path, line number, hunk, or raw review output")
      assertContains(rendered, "Persist full location-bearing review evidence")
    }
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
