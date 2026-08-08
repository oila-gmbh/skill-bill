package skillbill.contracts.goalplanning

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SKILL-174: an `agent/` tree under an exclusion-list root can never be read into planning memory, so
 * it must not exist in the repository at all. The oracle is the working tree, not the git index or
 * HEAD, so the assertion is independent of staging state and matches what discovery actually walks.
 */
class ExcludedRootAgentTreeAbsenceTest {
  @Test
  fun `no working tree path under an excluded root contains an agent segment`() {
    val repoRoot = repoRoot()
    assumeTrue(repoRoot != null, "not running inside a git checkout")

    val offenders = workingTreeDirectories(requireNotNull(repoRoot)).filter { path ->
      GoalPlanningDiscoveryExclusions.isExcluded(path) && path.split("/").contains("agent")
    }

    assertEquals(emptyList(), offenders, "delete these agent/ trees; excluded roots carry no boundary memory")
  }

  @Test
  fun `boundary writer skills forbid agent trees under excluded roots`() {
    val repoRoot = repoRoot()
    assumeTrue(repoRoot != null, "not running inside a git checkout")
    listOf("skills/bill-boundary-history/content.md", "skills/bill-boundary-decisions/content.md").forEach { path ->
      // Installed skill bodies may not name orchestration/ paths, so they carry the rule inline.
      val content = Files.readString(requireNotNull(repoRoot).resolve(path))
      assertEquals(
        true,
        content.contains("never create `agent/` under `platform-packs/`") &&
          content.contains("goal-planning discovery exclusion contract"),
        "$path must forbid agent/ under excluded roots and name the exclusion contract as the authority",
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

  /** Repo-relative directories, with build-noise directory names pruned so the walk stays bounded. */
  private fun workingTreeDirectories(repoRoot: Path): List<String> {
    val found = mutableListOf<String>()
    val pending = ArrayDeque(listOf(repoRoot))
    while (pending.isNotEmpty()) {
      val children = runCatching {
        Files.list(pending.removeFirst()).use { entries ->
          entries.filter { path -> Files.isDirectory(path) }.toList()
        }
      }.getOrDefault(emptyList())
      for (child in children) {
        if (child.fileName.toString() in GoalPlanningDiscoveryExclusions.excludedDirectoryNames) continue
        found.add(repoRoot.relativize(child).joinToString("/"))
        pending.add(child)
      }
    }
    return found
  }
}
