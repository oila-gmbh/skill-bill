package skillbill.scaffold

import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityCheckRepairWindowConformanceTest {
  @Test
  fun `maintained quality-check sidecars forbid per-fix rerun language`() {
    val forbiddenPatterns = listOf(
      Regex("after each fix", RegexOption.IGNORE_CASE),
      Regex("re-run targeted", RegexOption.IGNORE_CASE),
      Regex("re-run the smallest", RegexOption.IGNORE_CASE),
      Regex("rerun the narrow", RegexOption.IGNORE_CASE),
    )
    val repoRoot = repoRootFromTest()
    val qualityCheckFiles = Files.walk(repoRoot.resolve("platform-packs")).use { stream ->
      stream
        .filter { path ->
          path.fileName.toString() == "content.md" &&
            path.parent.fileName.toString().startsWith("bill-") &&
            path.toString().contains("/quality-check/")
        }
        .sorted()
        .toList()
    }
    assertTrue(qualityCheckFiles.size >= 8, "Expected at least eight maintained quality-check sidecars.")
    qualityCheckFiles.forEach { contentFile ->
      val content = Files.readString(contentFile)
      forbiddenPatterns.forEach { pattern ->
        assertFalse(
          pattern.containsMatchIn(content),
          "${repoRoot.relativize(contentFile)} must not contain per-fix rerun language matching $pattern",
        )
      }
      assertTrue(
        content.contains("Repair Window") && content.contains("do not invoke"),
        "${repoRoot.relativize(contentFile)} must state the repair window and forbidden-command rule",
      )
    }
  }
}
