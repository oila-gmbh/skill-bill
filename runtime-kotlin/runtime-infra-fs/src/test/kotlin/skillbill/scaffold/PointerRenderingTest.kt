package skillbill.scaffold

import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.pointer.normalizePointerPath
import skillbill.scaffold.pointer.renderPointer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerRenderingTest {
  private val tempRoot: Path = Files.createTempDirectory("skillbill-pointer-render-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(tempRoot)) {
      Files.walk(tempRoot).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `renders pointer to a target inside the same pack`() {
    val packRoot = tempRoot.resolve("platform-packs/kmp")
    val targetRel = "addons/android-compose-review.md"
    Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(packRoot.resolve(targetRel), "addon body")
    Files.createDirectories(packRoot.resolve("code-review/bill-kmp-code-review"))

    val rendered = renderPointer(
      repoRoot = tempRoot,
      packRoot = packRoot,
      spec = PointerSpec(
        skillRelativeDir = "code-review/bill-kmp-code-review",
        name = "android-compose-review.md",
        target = "platform-packs/kmp/addons/android-compose-review.md",
      ),
    )

    assertEquals("../../addons/android-compose-review.md", rendered)
    assertFalse(rendered.endsWith("\n"), "rendered pointer must not end with a newline")
  }

  @Test
  fun `renders pointer to addon under the same pack as a deep relative path`() {
    val packRoot = tempRoot.resolve("platform-packs/kmp")
    Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(packRoot.resolve("addons/edge.md"), "x")
    Files.createDirectories(packRoot.resolve("code-review/bill-kmp-code-review-ui"))

    val rendered = renderPointer(
      repoRoot = tempRoot,
      packRoot = packRoot,
      spec = PointerSpec(
        skillRelativeDir = "code-review/bill-kmp-code-review-ui",
        name = "android-compose-edge-to-edge.md",
        target = "platform-packs/kmp/addons/edge.md",
      ),
    )

    assertEquals("../../addons/edge.md", rendered)
  }

  @Test
  fun `renders deeply nested target requiring four parent traversals`() {
    val packRoot = tempRoot.resolve("platform-packs/kotlin")
    val orchestrationDir = tempRoot.resolve("orchestration/shell-content-contract")
    Files.createDirectories(orchestrationDir)
    Files.writeString(orchestrationDir.resolve("shell-ceremony.md"), "y")
    Files.createDirectories(packRoot.resolve("code-review/bill-kotlin-code-review-api-contracts"))

    val rendered = renderPointer(
      repoRoot = tempRoot,
      packRoot = packRoot,
      spec = PointerSpec(
        skillRelativeDir = "code-review/bill-kotlin-code-review-api-contracts",
        name = "shell-ceremony.md",
        target = "orchestration/shell-content-contract/shell-ceremony.md",
      ),
    )

    assertEquals("../../../../orchestration/shell-content-contract/shell-ceremony.md", rendered)
  }

  @Test
  fun `fails when the target file does not exist`() {
    val packRoot = tempRoot.resolve("platform-packs/kotlin")
    Files.createDirectories(packRoot.resolve("code-review/bill-kotlin-code-review"))

    val error = assertFailsWith<IllegalArgumentException> {
      renderPointer(
        repoRoot = tempRoot,
        packRoot = packRoot,
        spec = PointerSpec(
          skillRelativeDir = "code-review/bill-kotlin-code-review",
          name = "shell-ceremony.md",
          target = "orchestration/missing/shell-ceremony.md",
        ),
      )
    }
    assertTrue(
      error.message?.contains("does not exist") == true,
      "expected missing-target error, got '${error.message}'",
    )
  }

  @Test
  fun `fails with IllegalArgumentException when pointer file resolves to itself`() {
    // F-006: regression for the self-reference guard, which now raises IllegalArgumentException
    // (per F-001) so it is caught by runWithUpgradeRollback and the file is restored.
    val packRoot = tempRoot.resolve("platform-packs/kotlin")
    val pointerDir = packRoot.resolve("code-review/bill-kotlin-code-review")
    Files.createDirectories(pointerDir)
    Files.writeString(pointerDir.resolve("self.md"), "# self")

    val error = assertFailsWith<IllegalArgumentException> {
      renderPointer(
        repoRoot = tempRoot,
        packRoot = packRoot,
        spec = PointerSpec(
          skillRelativeDir = "code-review/bill-kotlin-code-review",
          name = "self.md",
          target = "platform-packs/kotlin/code-review/bill-kotlin-code-review/self.md",
        ),
      )
    }
    assertTrue(
      error.message?.contains("resolves to itself") == true,
      "expected self-reference error, got '${error.message}'",
    )
  }

  @Test
  fun `fails with IllegalArgumentException when target escapes repoRoot`() {
    // F-010: defense-in-depth guard for absolute-path/escape targets at render time.
    val outsideRepo = Files.createTempDirectory("skillbill-outside-repo-")
    try {
      Files.writeString(outsideRepo.resolve("evil.md"), "# evil")
      val packRoot = tempRoot.resolve("platform-packs/kotlin")
      Files.createDirectories(packRoot.resolve("code-review/skill"))
      val escapingRelative = tempRoot.toAbsolutePath().normalize().relativize(
        outsideRepo.resolve("evil.md").toAbsolutePath().normalize(),
      ).toString().replace('\\', '/')

      val error = assertFailsWith<IllegalArgumentException> {
        renderPointer(
          repoRoot = tempRoot,
          packRoot = packRoot,
          spec = PointerSpec(
            skillRelativeDir = "code-review/skill",
            name = "evil.md",
            target = escapingRelative,
          ),
        )
      }
      assertTrue(
        error.message?.contains("escapes repoRoot") == true,
        "expected escape error, got '${error.message}'",
      )
    } finally {
      Files.walk(outsideRepo).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `fails with IllegalArgumentException when target is a directory not a regular file`() {
    // F-010: directories must not be accepted as pointer targets.
    val packRoot = tempRoot.resolve("platform-packs/kotlin")
    Files.createDirectories(packRoot.resolve("code-review/skill"))
    Files.createDirectories(tempRoot.resolve("orchestration/dir-target"))

    val error = assertFailsWith<IllegalArgumentException> {
      renderPointer(
        repoRoot = tempRoot,
        packRoot = packRoot,
        spec = PointerSpec(
          skillRelativeDir = "code-review/skill",
          name = "dir-target.md",
          target = "orchestration/dir-target",
        ),
      )
    }
    assertTrue(
      error.message?.contains("does not exist") == true,
      "expected directory rejection (handled via 'does not exist'), got '${error.message}'",
    )
  }

  @Test
  fun `normalizePointerPath collapses Windows separators leading dot and double slashes`() {
    // F-008: direct unit tests of the path-normalization helper to replace the old tautological
    // forward-slash assertion that exercised pre-normalized inputs.
    assertEquals("a/b/c.md", normalizePointerPath("a\\b\\c.md"))
    assertEquals("a.md", normalizePointerPath("./a.md"))
    assertEquals("a/b.md", normalizePointerPath("a//b.md"))
    assertEquals("a/b/c.md", normalizePointerPath("a\\b//c.md"))
    assertEquals("../../shared/x.md", normalizePointerPath("..\\..\\shared\\x.md"))
  }

  @Test
  fun `rendered relative paths use forward slashes only`() {
    val packRoot = tempRoot.resolve("platform-packs/kmp")
    Files.createDirectories(packRoot.resolve("code-review/bill-kmp-code-review"))
    val orchestrationDir = tempRoot.resolve("orchestration/review-orchestrator")
    Files.createDirectories(orchestrationDir)
    Files.writeString(orchestrationDir.resolve("PLAYBOOK.md"), "z")

    val rendered = renderPointer(
      repoRoot = tempRoot,
      packRoot = packRoot,
      spec = PointerSpec(
        skillRelativeDir = "code-review/bill-kmp-code-review",
        name = "review-orchestrator.md",
        target = "orchestration/review-orchestrator/PLAYBOOK.md",
      ),
    )

    assertFalse('\\' in rendered, "rendered pointer must not contain backslashes: '$rendered'")
    assertFalse(rendered.startsWith("./"), "rendered pointer must not have a leading ./")
    assertFalse("//" in rendered, "rendered pointer must not contain double slashes")
  }
}
