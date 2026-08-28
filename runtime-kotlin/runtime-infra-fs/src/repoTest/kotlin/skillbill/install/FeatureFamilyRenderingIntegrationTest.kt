package skillbill.install

import skillbill.install.staging.stageInstalledSkill
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

    val kmpManifest = loadPlatformManifest(repoRoot.resolve("platform-packs/kmp"))
    val staged = stageInstalledSkill(
      repoRoot,
      repoRoot.resolve("skills/bill-feature"),
      home,
      manifests = listOf(kmpManifest),
      selectedPlatformSlugs = setOf("kmp"),
    )
    val stagedReview = stageInstalledSkill(repoRoot, repoRoot.resolve("skills/bill-code-review"), home)

    val feature = staged.renderedSkillFile.readText()

    assertContains(feature, "skill-bill goal preflight <issue-key> --format json")
    assertEquals(1, "skill-bill goal preflight".toRegex().findAll(feature).count())
    assertContains(feature, "concise human-readable summary")
    assertContains(feature, "issue key, feature name, child agent")
    assertContains(feature, "Do not print the raw JSON")
    assertFalse(feature.contains("Print the returned `gate_block` verbatim"))
    assertContains(feature, "Do not launch while unconfirmed")
    assertContains(feature, "For each entry in `rehydrate_targets`")
    assertContains(feature, "Fetch nothing when the list is empty")
    assertContains(feature, "Relay its")
    assertFalse(feature.contains("spec_source"))
    assertFalse(feature.contains("continuation lookup"))
    assertFalse(feature.contains("mcp__linear"))
    assertFalse(feature.contains("goal_observability"))
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
    listOf(
      "bill-feature-task.md",
      "bill-feature-task-runtime.md",
      "bill-feature-goal.md",
    ).forEach { removedSidecar ->
      assertFalse(Files.exists(staged.stagingDir.resolve(removedSidecar)), removedSidecar)
    }
    assertContains(feature, "code-review:auto|inline")
    assertFalse(feature.contains("code-review:auto|inline|delegated"))
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
