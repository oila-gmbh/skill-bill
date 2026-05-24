package skillbill.infrastructure.fs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorkflowGitOperationsTest {
  @Test
  fun `create commit leaves decomposition manifest uncommitted`() {
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
    assertFalse("decomposition-manifest.yaml" in committedFiles)
    assertEquals("?? .feature-specs/", git(repoRoot, "status", "--short").lineSequence().first())
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
