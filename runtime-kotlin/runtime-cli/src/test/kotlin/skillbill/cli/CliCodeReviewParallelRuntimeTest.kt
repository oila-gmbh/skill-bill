package skillbill.cli

import skillbill.cli.codereview.resolveCodeReviewRevisions
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliCodeReviewParallelRuntimeTest {
  @Test
  fun `code-review-parallel command is registered and shows help`() {
    val help = CliRuntime.run(listOf("code-review-parallel", "--help"), parallelReviewContext())

    assertEquals(0, help.exitCode, help.stdout)
    assertContains(help.stdout, "Removed")
  }

  @Test
  fun `code-review help documents an optional commit target`() {
    val help = CliRuntime.run(listOf("code-review", "--help"), parallelReviewContext())

    assertEquals(0, help.exitCode, help.stdout)
    assertContains(help.stdout, "Commit to review against its first parent.")
  }

  @Test
  fun `commit target derives parent and head revisions`() {
    assertEquals(
      "2e17a490e025a5b947e03bf67e41eaa589319960^" to "2e17a490e025a5b947e03bf67e41eaa589319960",
      resolveCodeReviewRevisions(
        commitTarget = "2e17a490e025a5b947e03bf67e41eaa589319960",
        baseRevision = "ignored-base",
        headRevision = "ignored-head",
      ),
    )
  }

  @Test
  fun `blank commit target falls through to explicit revisions`() {
    assertEquals(
      "abc" to "def",
      resolveCodeReviewRevisions(commitTarget = "", baseRevision = "abc", headRevision = "def"),
    )
    assertEquals(
      "abc" to "def",
      resolveCodeReviewRevisions(commitTarget = "   ", baseRevision = "abc", headRevision = "def"),
    )
    assertEquals(
      null to null,
      resolveCodeReviewRevisions(commitTarget = "", baseRevision = "", headRevision = "   "),
    )
  }

  @Test
  fun `code-review rejects a commit target combined with an exact diff`() {
    val tempDir = createGitRepo()
    val result = CliRuntime.run(
      listOf(
        "code-review",
        "2e17a490e025a5b947e03bf67e41eaa589319960",
        "--agent1",
        "claude",
        "--diff-file",
        tempDir.resolve("review.diff").toString(),
        "--repo-root",
        tempDir.toString(),
      ),
      parallelReviewContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "cannot be combined with --diff-file")
  }

  @Test
  fun `code-review rejects a commit target combined with a non-default scope`() {
    val tempDir = createGitRepo()
    val result = CliRuntime.run(
      listOf(
        "code-review",
        "2e17a490e025a5b947e03bf67e41eaa589319960",
        "--agent1",
        "claude",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      parallelReviewContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "cannot be combined with --scope 'staged'")
  }

  @Test
  fun `code-review rejects a commit target combined with explicit revisions`() {
    val tempDir = createGitRepo()
    val withBase = CliRuntime.run(
      listOf(
        "code-review",
        "2e17a490e025a5b947e03bf67e41eaa589319960",
        "--agent1",
        "claude",
        "--base-revision",
        "abc",
        "--repo-root",
        tempDir.toString(),
      ),
      parallelReviewContext(),
    )
    val withHead = CliRuntime.run(
      listOf(
        "code-review",
        "2e17a490e025a5b947e03bf67e41eaa589319960",
        "--agent1",
        "claude",
        "--head-revision",
        "def",
        "--repo-root",
        tempDir.toString(),
      ),
      parallelReviewContext(),
    )

    assertEquals(1, withBase.exitCode, withBase.stdout)
    assertContains(withBase.stdout, "cannot be combined with --base-revision or --head-revision")
    assertEquals(1, withHead.exitCode, withHead.stdout)
    assertContains(withHead.stdout, "cannot be combined with --base-revision or --head-revision")
  }

  @Test
  fun `code-review treats blank explicit revisions as unset beside a commit target`() {
    val tempDir = createGitRepo()
    val result = CliRuntime.run(
      listOf(
        "code-review",
        "2e17a490e025a5b947e03bf67e41eaa589319960",
        "--agent1",
        "claude",
        "--base-revision",
        "",
        "--head-revision",
        "",
        "--repo-root",
        tempDir.toString(),
      ),
      parallelReviewContext(),
    )

    assertFalse(
      result.stdout.contains("cannot be combined with --base-revision or --head-revision"),
      result.stdout,
    )
  }

  @Test
  fun `code-review-parallel run fails loud that dual-agent lanes are removed`() {
    val tempDir = createGitRepo()
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1",
        "claude",
        "--agent2",
        "codex",
        "--repo-root",
        tempDir.toString(),
      ),
      parallelReviewContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "dual-agent")
    assertContains(result.stdout, "code-review")
  }

  @Test
  fun `code-review rejects agent2 because dual-agent lanes are disconnected`() {
    val tempDir = createGitRepo()
    val result = CliRuntime.run(
      listOf(
        "code-review",
        "--agent1",
        "claude",
        "--agent2",
        "codex",
        "--repo-root",
        tempDir.toString(),
      ),
      parallelReviewContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Dual-agent")
  }

  @Test
  fun `code-review refuses to guess an agent when only a codex config path is exported`() {
    val tempDir = createGitRepo()
    val result = CliRuntime.run(
      listOf("code-review", "--repo-root", tempDir.toString()),
      parallelReviewContext(environment = mapOf("CODEX_HOME" to "/home/dev/.codex")),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Cannot determine the invoking agent")
    assertContains(result.stdout, "--agent1")
  }

  private fun createGitRepo(): Path {
    val dir = Files.createTempDirectory("cli-parallel-review-git")
    runGit("init", dir.toString())
    runGit("-C", dir.toString(), "config", "user.email", "test@test.com")
    runGit("-C", dir.toString(), "config", "user.name", "Test")
    runGit("-C", dir.toString(), "config", "commit.gpgSign", "false")
    runGit("-C", dir.toString(), "commit", "--allow-empty", "-m", "initial")
    return dir
  }

  private fun runGit(vararg args: String) {
    val process = ProcessBuilder("git", *args)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) {
      "git ${args.joinToString(" ")} failed: $output"
    }
  }

  private fun parallelReviewContext(
    environment: Map<String, String> = System.getenv(),
    agentRunLauncher: AgentRunLauncher? = null,
  ): CliRuntimeContext {
    val userHome = Files.createTempDirectory("cli-parallel-review-home")
    return CliRuntimeContext(
      environment = environment,
      userHome = userHome,
      agentRunLauncher = agentRunLauncher,
      executableLookup = ExecutableLookup { true },
      reviewNativeAgentPreflight = skillbill.ports.review.ReviewNativeAgentPreflightPort.NONE,
    )
  }
}
