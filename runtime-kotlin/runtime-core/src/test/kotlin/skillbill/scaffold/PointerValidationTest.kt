package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerValidationTest {
  private val temp: Path = Files.createTempDirectory("skillbill-pointer-validation-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(temp)) {
      Files.walk(temp).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `passes when on-disk pointer matches the rendered output and no orphans exist`() {
    val repoRoot = setupBasicPack()
    writePointer(
      repoRoot,
      "platform-packs/fixturepack/code-review/skill/shell-ceremony.md",
      "../../../../shared/shell.md",
    )

    val report = validatePlatformPackPointers(repoRoot)

    assertTrue(report.passed, "expected pass; got issues: ${report.issues}")
  }

  @Test
  fun `flags drift when on-disk pointer content does not match the renderer output`() {
    val repoRoot = setupBasicPack()
    writePointer(repoRoot, "platform-packs/fixturepack/code-review/skill/shell-ceremony.md", "../../../wrong/shell.md")

    val report = validatePlatformPackPointers(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { "drifted from manifest" in it },
      "expected drift issue, got ${report.issues}",
    )
  }

  @Test
  fun `flags missing when a declared pointer file is absent on disk`() {
    val repoRoot = setupBasicPack()
    // declared but not written: shell-ceremony.md

    val report = validatePlatformPackPointers(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { "declared pointer is missing on disk" in it },
      "expected missing issue, got ${report.issues}",
    )
  }

  @Test
  fun `does not flag a multi-line markdown file as a pointer orphan`() {
    // F-016 negative case (i): a non-pointer-shaped markdown file in a specialist directory must
    // not be flagged orphan.
    val repoRoot = setupBasicPack()
    writePointer(
      repoRoot,
      "platform-packs/fixturepack/code-review/skill/shell-ceremony.md",
      "../../../../shared/shell.md",
    )
    val specialistDir = repoRoot.resolve("platform-packs/fixturepack/code-review/specialist")
    Files.createDirectories(specialistDir)
    Files.writeString(
      specialistDir.resolve("notes.md"),
      "# Notes\n\nThis is a multi-line markdown file with prose.\nMore lines here.\n",
    )

    val report = validatePlatformPackPointers(repoRoot)

    assertTrue(report.passed, "expected pass; got issues: ${report.issues}")
  }

  @Test
  fun `does not flag a markdown file larger than the pointer size limit`() {
    // F-016 negative case (ii): files larger than the 500-byte cap must not be flagged orphan.
    val repoRoot = setupBasicPack()
    writePointer(
      repoRoot,
      "platform-packs/fixturepack/code-review/skill/shell-ceremony.md",
      "../../../../shared/shell.md",
    )
    val specialistDir = repoRoot.resolve("platform-packs/fixturepack/code-review/specialist")
    Files.createDirectories(specialistDir)
    val bigBody = "../../../../shared/shell.md\n" + "x".repeat(600)
    Files.writeString(specialistDir.resolve("big.md"), bigBody)

    val report = validatePlatformPackPointers(repoRoot)

    assertTrue(report.passed, "expected pass for >500b file; got issues: ${report.issues}")
  }

  @Test
  fun `does not flag pointer-shaped files inside addons or native-agents subtrees`() {
    // F-011: orphan walk must skip addons/ and native-agents/ subdirectories.
    val repoRoot = setupBasicPack()
    writePointer(
      repoRoot,
      "platform-packs/fixturepack/code-review/skill/shell-ceremony.md",
      "../../../../shared/shell.md",
    )
    val addons = repoRoot.resolve("platform-packs/fixturepack/addons")
    val native = repoRoot.resolve("platform-packs/fixturepack/native-agents")
    Files.createDirectories(addons)
    Files.createDirectories(native)
    Files.writeString(addons.resolve("inside-addon.md"), "../../../../shared/shell.md")
    Files.writeString(native.resolve("inside-native.md"), "../../../../shared/shell.md")

    val report = validatePlatformPackPointers(repoRoot)

    assertTrue(report.passed, "expected pass; got issues: ${report.issues}")
  }

  @Test
  fun `accumulates multiple issues across packs and returns sorted list`() {
    // F-017: drift in pack A + missing in pack B; assert both reported, sorted, passed=false.
    val repoRoot = temp.resolve("multi-issue-repo")
    Files.createDirectories(repoRoot.resolve("shared"))
    Files.writeString(repoRoot.resolve("shared/shell.md"), "# shell")
    listOf("apack", "bpack").forEach { slug ->
      val packRoot = repoRoot.resolve("platform-packs/$slug")
      Files.createDirectories(packRoot.resolve("code-review/skill"))
      Files.writeString(
        packRoot.resolve("platform.yaml"),
        """
        platform: $slug
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
            - name: shell-ceremony.md
              target: shared/shell.md
        """.trimIndent(),
      )
    }
    // pack 'apack' has drift; pack 'bpack' is missing on disk.
    writePointer(
      repoRoot,
      "platform-packs/apack/code-review/skill/shell-ceremony.md",
      "../../../wrong/shell.md",
    )

    val report = validatePlatformPackPointers(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { "drifted from manifest" in it && "apack" in it },
      "expected drift issue for apack, got ${report.issues}",
    )
    assertTrue(
      report.issues.any { "declared pointer is missing on disk" in it && "bpack" in it },
      "expected missing issue for bpack, got ${report.issues}",
    )
    assertEquals(report.issues.sorted(), report.issues, "expected issues to be sorted")
  }

  @Test
  fun `flags orphan when an undeclared pointer-shaped file lives in a pack`() {
    val repoRoot = setupBasicPack()
    writePointer(
      repoRoot,
      "platform-packs/fixturepack/code-review/skill/shell-ceremony.md",
      "../../../../shared/shell.md",
    )
    // Add an orphan in a sibling specialist directory
    val orphanDir = repoRoot.resolve("platform-packs/fixturepack/code-review/specialist")
    Files.createDirectories(orphanDir)
    Files.writeString(orphanDir.resolve("rogue.md"), "../../../../shared/shell.md")

    val report = validatePlatformPackPointers(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { "orphan pointer file" in it && "rogue.md" in it },
      "expected orphan issue, got ${report.issues}",
    )
  }

  /**
   * Writes a minimal repo with one platform pack named `fixturepack`, declaring a single pointer
   * `code-review/skill/shell-ceremony.md` -> `shared/shell.md`. Does NOT write the pointer file
   * itself; tests are responsible for placing or omitting it.
   */
  private fun setupBasicPack(): Path {
    val repoRoot = temp.resolve("repo")
    Files.createDirectories(repoRoot.resolve("shared"))
    Files.writeString(repoRoot.resolve("shared/shell.md"), "# shell")
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
          - name: shell-ceremony.md
            target: shared/shell.md
      """.trimIndent(),
    )
    return repoRoot
  }

  private fun writePointer(repoRoot: Path, relativePath: String, content: String) {
    val target = repoRoot.resolve(relativePath)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
  }
}
