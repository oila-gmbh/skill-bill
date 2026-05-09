package skillbill.scaffold

import skillbill.nativeagent.parseNativeAgentBundle
import skillbill.nativeagent.renderNativeAgentBundle
import skillbill.testsupport.SnapshotAssertions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthoringRenderSnapshotTest {
  @Test
  fun `standalone governed skill render matches snapshot`() {
    val repoRoot = currentRepoRootForSnapshotTest()

    val first = renderAuthoringTarget(repoRoot, "bill-pr-description").stdout
    val second = renderAuthoringTarget(repoRoot, "bill-pr-description").stdout

    assertEquals(first, second, "render output must be deterministic across repeated in-memory renders")
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/scaffold/bill-pr-description.render.txt",
      first,
    )
  }

  @Test
  fun `kotlin code-review render matches snapshot with manifest ordered pointers`() {
    val repoRoot = currentRepoRootForSnapshotTest()
    val rendered = renderAuthoringTarget(repoRoot, "bill-kotlin-code-review")
    val first = rendered.stdout
    val second = renderAuthoringTarget(repoRoot, "bill-kotlin-code-review").stdout

    assertEquals(
      expectedHeadersFromManifest(
        repoRoot,
        packSlug = "kotlin",
        skillRelativeDir = "code-review/bill-kotlin-code-review",
      ),
      rendered.blocks.map { block -> block.header },
    )
    assertEquals(first, second, "render output must be deterministic across repeated in-memory renders")
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/scaffold/bill-kotlin-code-review.render.txt",
      first,
    )
  }

  @Test
  fun `kmp ui code-review render and native agent bundle match snapshots`() {
    val repoRoot = currentRepoRootForSnapshotTest()
    val rendered = renderAuthoringTarget(repoRoot, "bill-kmp-code-review-ui")
    val first = rendered.stdout
    val second = renderAuthoringTarget(repoRoot, "bill-kmp-code-review-ui").stdout

    assertEquals(
      expectedHeadersFromManifest(repoRoot, packSlug = "kmp", skillRelativeDir = "code-review/bill-kmp-code-review-ui"),
      rendered.blocks.map { block -> block.header },
    )
    assertEquals(first, second, "render output must be deterministic across repeated in-memory renders")
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/scaffold/bill-kmp-code-review-ui.render.txt",
      first,
    )

    val nativeAgentPath = repoRoot.resolve(
      "platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/agents.yaml",
    )
    val nativeAgentSource = renderNativeAgentBundle(parseNativeAgentBundle(nativeAgentPath))
    assertEquals(
      SnapshotAssertions.normalizeLineEndings(Files.readString(nativeAgentPath)),
      nativeAgentSource,
      "native agent source render must stay byte-identical after line-ending normalization",
    )
    SnapshotAssertions.assertMatchesSnapshot(
      "snapshots/native-agents/bill-kmp-code-review.agents.yaml",
      nativeAgentSource,
    )
  }

  private fun currentRepoRootForSnapshotTest(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      if (looksLikeRepoRoot(current)) {
        return current
      }
      current = current.parent
    }
    error("Could not locate skill-bill repo root for snapshot test")
  }

  private fun looksLikeRepoRoot(candidate: Path): Boolean =
    Files.isRegularFile(candidate.resolve("runtime-kotlin/settings.gradle.kts")) &&
      Files.isDirectory(candidate.resolve("skills")) &&
      Files.isDirectory(candidate.resolve("platform-packs"))

  private fun expectedHeadersFromManifest(repoRoot: Path, packSlug: String, skillRelativeDir: String): List<String> {
    val pack = loadPlatformManifest(repoRoot.resolve("platform-packs/$packSlug"))
    return listOf("===== SKILL.md: platform-packs/$packSlug/$skillRelativeDir/SKILL.md =====") +
      pack.pointers
        .filter { spec -> spec.skillRelativeDir == skillRelativeDir }
        .map { spec -> "===== pointer: platform-packs/$packSlug/$skillRelativeDir/${spec.name} =====" }
  }
}
