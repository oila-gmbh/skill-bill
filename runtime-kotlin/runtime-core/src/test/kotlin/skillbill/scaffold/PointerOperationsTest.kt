package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerOperationsTest {
  private val temp: Path = Files.createTempDirectory("skillbill-pointer-ops-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(temp)) {
      Files.walk(temp).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `regenerate is a no-op when platform-packs directory is absent`() {
    val repoRoot = temp.resolve("repo-with-no-packs")
    Files.createDirectories(repoRoot)
    val result = PointerOperations.regenerate(repoRoot)
    assertTrue(result.regeneratedFiles.isEmpty(), "expected empty list, got ${result.regeneratedFiles}")
  }

  @Test
  fun `second regenerate writes nothing when on-disk pointers already match`() {
    val repoRoot = setupRepoWithTwoPointers()
    val first = PointerOperations.regenerate(repoRoot)
    assertTrue(first.regeneratedFiles.isNotEmpty(), "first regenerate should have written pointers")

    val second = PointerOperations.regenerate(repoRoot)
    assertTrue(
      second.regeneratedFiles.isEmpty(),
      "expected idempotent re-run; got ${second.regeneratedFiles}",
    )
  }

  @Test
  fun `regenerate returns regeneratedFiles sorted by absolute path`() {
    val repoRoot = setupRepoWithTwoPointers()
    val result = PointerOperations.regenerate(repoRoot)
    val rendered = result.regeneratedFiles.map { it.toString() }
    assertEquals(rendered.sorted(), rendered, "expected regeneratedFiles to be sorted")
  }

  @Test
  fun `createdPaths contains only files that did not previously exist`() {
    val repoRoot = setupRepoWithTwoPointers()
    val pointerB = repoRoot.resolve(
      "platform-packs/fixturepack/code-review/skill/b.md",
    )
    Files.createDirectories(pointerB.parent)
    Files.writeString(pointerB, "../../../shared/b.md") // pre-existing, will be re-rendered
    val original = Files.readAllBytes(pointerB)

    val originalBytes = mutableMapOf<Path, ByteArray>()
    val createdPaths = mutableListOf<Path>()
    val result = PointerOperations.regenerate(
      repoRoot,
      originalBytes = originalBytes,
      createdPaths = createdPaths,
    )

    assertTrue(result.regeneratedFiles.isNotEmpty(), "expected pointers to be written")
    val createdNames = createdPaths.map { it.fileName.toString() }
    assertTrue("a.md" in createdNames, "expected newly-created a.md to be tracked: $createdNames")
    assertFalse("b.md" in createdNames, "expected pre-existing b.md to NOT be in createdPaths: $createdNames")
    // originalBytes records the pre-existing bytes for b.md
    assertContentEquals(
      original,
      originalBytes[pointerB.normalize()] ?: error("expected originalBytes entry for $pointerB"),
    )
  }

  @Test
  fun `originalBytes is sticky across multiple regenerate calls and not clobbered by overwrites`() {
    val repoRoot = setupRepoWithTwoPointers()
    val pointerA = repoRoot.resolve(
      "platform-packs/fixturepack/code-review/skill/a.md",
    ).normalize()
    Files.createDirectories(pointerA.parent)
    val veryFirstBytes = "ORIGINAL CONTENT".toByteArray(Charsets.UTF_8)
    Files.write(pointerA, veryFirstBytes)

    val originalBytes = mutableMapOf<Path, ByteArray>()
    val createdPaths = mutableListOf<Path>()

    // First regenerate -> renderer rewrites pointerA, originalBytes captures veryFirstBytes.
    PointerOperations.regenerate(repoRoot, originalBytes, createdPaths)
    assertContentEquals(
      veryFirstBytes,
      originalBytes[pointerA] ?: error("expected originalBytes entry after first regenerate"),
    )

    // Manually drift pointerA to simulate a second tool overwrite, then call regenerate again.
    Files.write(pointerA, "DRIFTED".toByteArray(Charsets.UTF_8))
    PointerOperations.regenerate(repoRoot, originalBytes, createdPaths)

    // Second regenerate must not clobber originalBytes — the very first bytes win, so
    // rollback restores the pre-tool state, not the intermediate drift.
    assertContentEquals(
      veryFirstBytes,
      originalBytes[pointerA] ?: error("expected originalBytes still set after second regenerate"),
      "originalBytes must hold the FIRST pre-existing bytes, not the drifted bytes",
    )
  }

  /**
   * Lays out a repo with one platform pack `fixturepack` declaring two pointers, with their
   * targets present on disk. Pointer files themselves are NOT pre-written by this helper.
   */
  private fun setupRepoWithTwoPointers(): Path {
    val repoRoot = temp.resolve("repo-${System.nanoTime()}")
    Files.createDirectories(repoRoot.resolve("shared"))
    Files.writeString(repoRoot.resolve("shared/a.md"), "# a")
    Files.writeString(repoRoot.resolve("shared/b.md"), "# b")
    val packRoot = repoRoot.resolve("platform-packs/fixturepack")
    Files.createDirectories(packRoot.resolve("code-review/skill"))
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: fixturepack
      contract_version: "1.1"

      routing_signals:
        strong:
          - ".fixture"

      declared_code_review_areas: []

      declared_files:
        baseline: code-review/skill/SKILL.md

      area_metadata: {}

      pointers:
        code-review/skill:
          - name: a.md
            target: shared/a.md
          - name: b.md
            target: shared/b.md
      """.trimIndent(),
    )
    return repoRoot
  }
}
