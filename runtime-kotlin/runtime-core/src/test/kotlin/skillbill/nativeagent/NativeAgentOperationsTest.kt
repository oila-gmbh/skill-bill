package skillbill.nativeagent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAgentOperationsTest {
  @Test
  fun `installCacheRoot uses sanitized slug from platform-packs parent directory`() {
    val home = Files.createTempDirectory("skillbill-cache-typical")
    val repoRoot = Files.createDirectories(home.resolve("my-repo"))
    val platformPacks = Files.createDirectories(repoRoot.resolve("platform-packs"))
    val skills = Files.createDirectories(repoRoot.resolve("skills"))

    val cache = NativeAgentOperations.installCacheRoot(home, platformPacks, skills)

    val leaf = cache.fileName.toString()
    assertTrue(leaf.startsWith("my-repo-"), "expected slug 'my-repo' prefix in $leaf")
    val hash = leaf.removePrefix("my-repo-")
    assertTrue(hash.matches(Regex("[0-9a-f]{16}")), "expected 8-byte hex hash tail in $leaf")
    assertEquals(home.resolve(".skill-bill/native-agents").toAbsolutePath().normalize(), cache.parent)
  }

  @Test
  fun `installCacheRoot lowercases and collapses mixed-case names with spaces`() {
    val home = Files.createTempDirectory("skillbill-cache-mixed")
    val repoRoot = Files.createDirectories(home.resolve("Mixed Case Repo!"))
    val platformPacks = Files.createDirectories(repoRoot.resolve("platform-packs"))

    val cache = NativeAgentOperations.installCacheRoot(home, platformPacks, null)

    val leaf = cache.fileName.toString()
    assertTrue(leaf.startsWith("mixed-case-repo-"), "expected sanitized slug in $leaf")
    val hash = leaf.removePrefix("mixed-case-repo-")
    assertTrue(hash.matches(Regex("[0-9a-f]{16}")))
  }

  @Test
  fun `installCacheRoot truncates slug to 32 characters`() {
    val home = Files.createTempDirectory("skillbill-cache-long")
    val repoRoot = Files.createDirectories(home.resolve("a".repeat(50)))
    val platformPacks = Files.createDirectories(repoRoot.resolve("platform-packs"))

    val cache = NativeAgentOperations.installCacheRoot(home, platformPacks, null)

    val leaf = cache.fileName.toString()
    val slug = leaf.substringBeforeLast('-')
    assertEquals("a".repeat(32), slug, "slug must be truncated to 32 chars; got $leaf")
    assertTrue(
      leaf.matches(Regex("a{32}-[0-9a-f]{16}")),
      "leaf must be exactly <32-char slug>-<16-hex-char hash>; got $leaf",
    )
  }

  @Test
  fun `installCacheRoot omits slug prefix when sanitization yields empty string`() {
    val home = Files.createTempDirectory("skillbill-cache-empty-slug")
    val repoRoot = Files.createDirectories(home.resolve("___"))
    val platformPacks = Files.createDirectories(repoRoot.resolve("platform-packs"))

    val cache = NativeAgentOperations.installCacheRoot(home, platformPacks, null)

    val leaf = cache.fileName.toString()
    assertTrue(leaf.matches(Regex("[0-9a-f]{16}")), "expected hash-only leaf for empty slug; got $leaf")
    assertFalse(leaf.contains('_'))
  }

  @Test
  fun `regenerate filters source files before parsing selected skills`() {
    val home = Files.createTempDirectory("skillbill-targeted-regenerate-home")
    val repoRoot = Files.createTempDirectory("skillbill-targeted-regenerate-repo")
    val selectedSources = Files.createDirectories(repoRoot.resolve("skills/bill-selected/native-agents"))
    Files.writeString(
      selectedSources.resolve("bill-selected-worker.md"),
      """
      ---
      name: bill-selected-worker
      description: Selected worker.
      ---

      # Worker
      """.trimIndent(),
    )
    val unselectedSources = Files.createDirectories(repoRoot.resolve("skills/bill-unselected/native-agents"))
    Files.writeString(unselectedSources.resolve("agents.yaml"), "agents: [\n")

    val result = NativeAgentOperations.regenerate(
      repoRoot = repoRoot,
      skillNames = listOf("bill-selected"),
      home = home,
    )

    assertEquals(NativeAgentProvider.entries.size, result.regeneratedFiles.size)
    assertTrue(result.regeneratedFiles.all { path -> path.fileName.toString().startsWith("bill-selected-worker.") })
  }
}
