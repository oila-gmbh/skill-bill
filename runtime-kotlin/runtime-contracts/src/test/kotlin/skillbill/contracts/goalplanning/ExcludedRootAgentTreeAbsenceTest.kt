package skillbill.contracts.goalplanning

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SKILL-174: an `agent/` tree under an exclusion-list root can never be read into planning memory, so
 * it must not exist in the repository at all. Scope is tracked files — untracked noise roots such as
 * `build/` hold nothing to delete.
 */
class ExcludedRootAgentTreeAbsenceTest {
  @Test
  fun `no tracked path under an excluded root contains an agent segment`() {
    val repoRoot = repoRoot()
    assumeTrue(repoRoot != null, "not running inside a git checkout")
    val tracked = repoRoot?.let(::trackedFiles)
    assumeTrue(tracked != null, "git ls-files is unavailable")

    val offenders = tracked.orEmpty().filter { path ->
      GoalPlanningDiscoveryExclusions.isExcluded(path) && path.split("/").contains("agent")
    }

    assertEquals(emptyList(), offenders, "delete these agent/ trees; excluded roots carry no boundary memory")
  }

  @Test
  fun `boundary writer skills forbid agent trees under excluded roots`() {
    val repoRoot = repoRoot()
    assumeTrue(repoRoot != null, "not running inside a git checkout")
    listOf("skills/bill-boundary-history/content.md", "skills/bill-boundary-decisions/content.md").forEach { path ->
      val content = Files.readString(requireNotNull(repoRoot).resolve(path))
      assertEquals(
        true,
        content.contains(GoalPlanningDiscoveryExclusions.CONTRACT_FILE) && content.contains("never create `agent/`"),
        "$path must point at the exclusion contract and forbid agent/ under excluded roots",
      )
    }
  }

  private fun repoRoot(): Path? {
    var candidate: Path? = Path.of("").toAbsolutePath()
    while (candidate != null) {
      val gitEntry = candidate.resolve(".git")
      if (Files.isDirectory(gitEntry) || Files.isRegularFile(gitEntry)) return candidate
      candidate = candidate.parent
    }
    return null
  }

  private fun trackedFiles(repoRoot: Path): List<String>? = runCatching {
    val process = ProcessBuilder("git", "ls-files", "-z")
      .directory(File(repoRoot.toString()))
      .start()
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
    check(process.waitFor() == 0) { "git ls-files failed" }
    output.split('\u0000').filter { entry -> entry.isNotEmpty() }
  }.getOrNull()
}
