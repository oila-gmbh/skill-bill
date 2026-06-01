package skillbill.infrastructure.fs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitWorkflowGitOperationsTest {
  @Test
  fun `create commit includes staged decomposition manifest projection`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-workflow")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("runtime.txt"), "runtime change\n")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52-demo/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(manifestPath, "contract_version: \"0.1\"\n")
    git(repoRoot, "add", ".")

    val result = GitWorkflowGitOperations().createCommit(repoRoot, "SKILL-52 subtask 1: demo")

    assertTrue(result.ok, result.error)
    val committedFiles = git(repoRoot, "show", "--name-only", "--format=", "HEAD")
    assertContains(committedFiles, "runtime.txt")
    assertContains(committedFiles, ".feature-specs/SKILL-52-demo/decomposition-manifest.yaml")
    assertEquals("", git(repoRoot, "status", "--short"))
  }

  @Test
  fun `worktree activity summarizes changed files and diff stat`() {
    val repoRoot = Files.createTempDirectory("skillbill-git-worktree-activity")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    Files.writeString(repoRoot.resolve("tracked.txt"), "one\ntwo\n")
    Files.writeString(repoRoot.resolve("new.txt"), "new\n")

    val result = GitWorkflowGitOperations().worktreeActivity(repoRoot)

    assertTrue(result.ok, result.error)
    assertEquals(2, result.changedFileSummary?.total)
    assertEquals(1, result.changedFileSummary?.modified)
    assertEquals(1, result.changedFileSummary?.untracked)
    assertEquals(1, result.diffStat?.filesChanged)
    assertEquals(1, result.diffStat?.insertions)
  }

  private fun git(repoRoot: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }
}
