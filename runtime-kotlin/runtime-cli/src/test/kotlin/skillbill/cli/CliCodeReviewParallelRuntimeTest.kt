package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliCodeReviewParallelRuntimeTest {
  @Test
  fun `code-review-parallel command is registered and shows help`() {
    val help = CliRuntime.run(listOf("code-review-parallel", "--help"), parallelReviewContext())

    assertEquals(0, help.exitCode, help.stdout)
    assertContains(help.stdout, "Removed")
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
