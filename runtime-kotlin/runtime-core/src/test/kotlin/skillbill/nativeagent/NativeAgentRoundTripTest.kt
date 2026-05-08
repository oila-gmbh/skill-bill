package skillbill.nativeagent

import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeAgentRoundTripTest {
  @Test
  fun `every native agent source survives parse render parse with structural equality`() {
    val repoRoot = findRepoRoot() ?: run {
      Assumptions.assumeTrue(
        false,
        "Skipping round-trip test: could not locate repo root (set SKILL_BILL_REPO_ROOT or run from inside repo)",
      )
      return
    }
    val sources = NativeAgentOperations.discoverRepoNativeAgentSources(repoRoot)
    assertTrue(sources.isNotEmpty(), "Expected at least one native agent source under skills/ or platform-packs/")

    sources.forEach { sourcePath ->
      val parsed = parseNativeAgentSource(sourcePath)
      val rendered = renderNativeAgentSource(parsed)
      val reparsed = parseNativeAgentSourceText(rendered, sourcePath.toString())

      assertEquals(parsed.name, reparsed.name, "name drift on round-trip for $sourcePath")
      assertEquals(parsed.description, reparsed.description, "description drift on round-trip for $sourcePath")
      assertEquals(parsed.body, reparsed.body, "body drift on round-trip for $sourcePath")
    }
  }

  private fun findRepoRoot(): Path? {
    val envRoot = System.getenv("SKILL_BILL_REPO_ROOT")
      ?.takeIf { it.isNotBlank() }
      ?.let { Path.of(it).toAbsolutePath().normalize() }
      ?.takeIf(::looksLikeRepoRoot)
    var current: Path? = envRoot ?: Path.of("").toAbsolutePath().normalize()
    var found: Path? = envRoot
    while (found == null && current != null) {
      if (looksLikeRepoRoot(current)) {
        found = current
      } else {
        current = current.parent
      }
    }
    return found
  }

  private fun looksLikeRepoRoot(candidate: Path): Boolean {
    val hasSettings = Files.isRegularFile(candidate.resolve("settings.gradle.kts")) ||
      Files.isRegularFile(candidate.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasSkills = Files.isDirectory(candidate.resolve("skills"))
    return hasSettings && hasSkills
  }
}
